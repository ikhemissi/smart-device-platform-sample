# Microsoft Foundry for the AI device analyst (ADR-0010, M6 design D6): an AI Services account with key-based access
# disabled, a project, the chat model deployment and the project's Application Insights connection.

terraform {
  required_providers {
    azapi = { source = "azure/azapi" }
  }
}

variable "resource_group_id" { type = string }
variable "location" { type = string }
variable "environment_name" { type = string }
variable "token" { type = string }
variable "model" {
  description = "Chat model deployment (name, version, SKU and capacity in thousands of tokens per minute)."
  type = object({
    name     = string
    version  = string
    sku      = string
    capacity = number
  })
}
variable "decision_model" {
  description = "Microsoft-Decision-1 deployment of the anomaly detector (M7 design D6) and the maintenance job (M9 design D4); null when both are off."
  type        = object({ capacity = number })
  default     = null
}
variable "application_insights" {
  type = object({
    id                = string
    connection_string = string
  })
}
variable "tags" { type = map(string) }

locals {
  account_name = "ais-${var.environment_name}-${var.token}"
  api_version  = "2025-06-01"
}

resource "azapi_resource" "account" {
  type      = "Microsoft.CognitiveServices/accounts@${local.api_version}"
  name      = local.account_name
  parent_id = var.resource_group_id
  location  = var.location
  tags      = var.tags

  identity {
    type = "SystemAssigned"
  }

  body = {
    kind = "AIServices"
    sku  = { name = "S0" }
    properties = {
      allowProjectManagement = true
      customSubDomainName    = local.account_name
      disableLocalAuth       = true
      publicNetworkAccess    = "Enabled"
    }
  }
}

resource "azapi_resource" "project" {
  type      = "Microsoft.CognitiveServices/accounts/projects@${local.api_version}"
  name      = "proj-${var.environment_name}"
  parent_id = azapi_resource.account.id
  location  = var.location
  tags      = var.tags

  identity {
    type = "SystemAssigned"
  }

  body = {
    properties = {
      displayName = "Smart Device Platform (${var.environment_name})"
      description = "AI device analyst (M6)."
    }
  }

  response_export_values = ["identity.principalId"]
}

resource "azapi_resource" "model" {
  type      = "Microsoft.CognitiveServices/accounts/deployments@${local.api_version}"
  name      = var.model.name
  parent_id = azapi_resource.account.id

  body = {
    sku = {
      name     = var.model.sku
      capacity = var.model.capacity
    }
    properties = {
      model = {
        format  = "OpenAI"
        name    = var.model.name
        version = var.model.version
      }
      versionUpgradeOption = "NoAutoUpgrade"
      raiPolicyName        = "Microsoft.DefaultV2"
    }
  }

  # Concurrent operations on the same account fail with a conflict.
  depends_on = [azapi_resource.project]
}

# Classifies anomaly candidates (ADR-0011). Preview model; Entra-only like the rest of the account.
resource "azapi_resource" "decision_model" {
  count = var.decision_model == null ? 0 : 1
  type  = "Microsoft.CognitiveServices/accounts/deployments@${local.api_version}"
  # Deployment names must not contain the reserved word "Microsoft"; requests name the deployment, not the model.
  name      = "decision-1"
  parent_id = azapi_resource.account.id

  body = {
    sku = {
      name     = "DataZoneStandard"
      capacity = var.decision_model.capacity
    }
    properties = {
      model = {
        format  = "Microsoft"
        name    = "Microsoft-Decision-1"
        version = "1"
      }
      versionUpgradeOption = "NoAutoUpgrade"
    }
  }

  # Concurrent operations on the same account fail with a conflict.
  depends_on = [azapi_resource.appinsights_connection]
}

# Documented Foundry form (foundry-samples connection-application-insights.bicep): the "key" is the ingestion
# connection string, an endpoint identifier rather than a credential; local authentication is disabled on
# Application Insights, so ingestion still requires an Entra token.
resource "azapi_resource" "appinsights_connection" {
  type      = "Microsoft.CognitiveServices/accounts/projects/connections@${local.api_version}"
  name      = "appinsights"
  parent_id = azapi_resource.project.id

  body = {
    properties = {
      category = "AppInsights"
      target   = var.application_insights.id
      authType = "ApiKey"
      # The service stores false for this category regardless of the request (true would drift on every plan).
      isSharedToAll = false
      credentials   = { key = var.application_insights.connection_string }
      metadata = {
        ApiType    = "Azure"
        ResourceId = var.application_insights.id
      }
    }
  }

  depends_on = [azapi_resource.model]
}

# Lets the Foundry portal (tracing, evaluations) read the agent traces, including GenAI content.
resource "azurerm_role_assignment" "project_traces" {
  for_each             = toset(["Log Analytics Reader", "Privileged Monitoring Data Reader"])
  scope                = var.application_insights.id
  role_definition_name = each.value
  principal_id         = azapi_resource.project.output.identity.principalId
  principal_type       = "ServicePrincipal"
}

output "account_id" { value = azapi_resource.account.id }
output "account_name" { value = azapi_resource.account.name }
output "project_id" { value = azapi_resource.project.id }
output "project_name" { value = azapi_resource.project.name }
output "openai_endpoint" { value = "https://${local.account_name}.openai.azure.com/" }
output "project_endpoint" { value = "https://${local.account_name}.services.ai.azure.com/api/projects/${azapi_resource.project.name}" }
output "model_deployment_name" { value = azapi_resource.model.name }
output "account_endpoint" { value = "https://${local.account_name}.cognitiveservices.azure.com/" }
output "decision_model_deployment_name" { value = join("", azapi_resource.decision_model[*].name) }
