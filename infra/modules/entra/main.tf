# Entra app registrations for user access (ADR-0007, M2 design D1): the platform API (single audience for every
# backend, app roles DeviceData.Read/Write, delegated scope user_impersonation) and the SPA (MSAL, PKCE).

variable "environment_name" { type = string }
variable "web_origin" {
  description = "Origin of the deployed portal (https://<static web app hostname>)."
  type        = string
}
variable "deployer_object_id" {
  description = "Deploying user, granted both roles for the demonstrator (null to skip)."
  type        = string
  default     = null
}
variable "reader_group_object_ids" {
  type    = list(string)
  default = []
}
variable "editor_group_object_ids" {
  type    = list(string)
  default = []
}
variable "preauthorize_azure_cli" {
  description = "Pre-authorize the Azure CLI on the API scope so operators can get tokens with az (dev only)."
  type        = bool
  default     = true
}

locals {
  azure_cli_client_id = "04b07795-8ddb-461a-bbee-02f9e1bf7b46"
}

resource "random_uuid" "scope" {}
resource "random_uuid" "role_read" {}
resource "random_uuid" "role_write" {}

data "azuread_client_config" "current" {}

resource "azuread_application" "api" {
  display_name     = "Smart Device Platform API (${var.environment_name})"
  sign_in_audience = "AzureADMyOrg"
  owners           = [data.azuread_client_config.current.object_id]

  api {
    requested_access_token_version = 2

    oauth2_permission_scope {
      id                         = random_uuid.scope.result
      value                      = "user_impersonation"
      type                       = "User"
      enabled                    = true
      admin_consent_display_name = "Access the Smart Device Platform API"
      admin_consent_description  = "Allows the application to call the Smart Device Platform API on behalf of the signed-in user."
      user_consent_display_name  = "Access the Smart Device Platform API"
      user_consent_description   = "Allows the application to call the Smart Device Platform API on your behalf."
    }
  }

  app_role {
    id                   = random_uuid.role_read.result
    value                = "DeviceData.Read"
    display_name         = "Query device data"
    description          = "Query devices, measurements, sites and alerts, and use the AI assistant."
    allowed_member_types = ["User"]
    enabled              = true
  }

  app_role {
    id                   = random_uuid.role_write.result
    value                = "DeviceData.Write"
    display_name         = "Edit device data"
    description          = "Everything in Query device data, plus editing device information, sites, site certificates and alert thresholds."
    allowed_member_types = ["User"]
    enabled              = true
  }

  lifecycle {
    # The identifier URI and pre-authorized clients are managed by their own resources below.
    ignore_changes = [identifier_uris, api[0].known_client_applications]
  }
}

# api://<appId>: accepted by the tenant's default app management policy (custom URIs may be rejected).
resource "azuread_application_identifier_uri" "api" {
  application_id = azuread_application.api.id
  identifier_uri = "api://${azuread_application.api.client_id}"
}

resource "azuread_service_principal" "api" {
  client_id = azuread_application.api.client_id
  # Users without a role still get a token: GET /me tells the portal to show the access-denied page; every data
  # operation refuses them (gateway and backend).
  app_role_assignment_required = false
  owners                       = [data.azuread_client_config.current.object_id]
}

resource "azuread_application" "web" {
  display_name     = "Smart Device Platform Web (${var.environment_name})"
  sign_in_audience = "AzureADMyOrg"
  owners           = [data.azuread_client_config.current.object_id]

  single_page_application {
    redirect_uris = ["${var.web_origin}/", "http://localhost:5173/"]
  }

  required_resource_access {
    resource_app_id = azuread_application.api.client_id
    resource_access {
      id   = random_uuid.scope.result
      type = "Scope"
    }
  }
}

resource "azuread_service_principal" "web" {
  client_id = azuread_application.web.client_id
  owners    = [data.azuread_client_config.current.object_id]
}

resource "azuread_application_pre_authorized" "web" {
  application_id       = azuread_application.api.id
  authorized_client_id = azuread_application.web.client_id
  permission_ids       = [random_uuid.scope.result]
}

resource "azuread_application_pre_authorized" "azure_cli" {
  count                = var.preauthorize_azure_cli ? 1 : 0
  application_id       = azuread_application.api.id
  authorized_client_id = local.azure_cli_client_id
  permission_ids       = [random_uuid.scope.result]
}

resource "azuread_app_role_assignment" "deployer" {
  for_each            = var.deployer_object_id == null ? {} : { read = random_uuid.role_read.result, write = random_uuid.role_write.result }
  app_role_id         = each.value
  principal_object_id = var.deployer_object_id
  resource_object_id  = azuread_service_principal.api.object_id
}

resource "azuread_app_role_assignment" "reader_groups" {
  for_each            = toset(var.reader_group_object_ids)
  app_role_id         = random_uuid.role_read.result
  principal_object_id = each.value
  resource_object_id  = azuread_service_principal.api.object_id
}

resource "azuread_app_role_assignment" "editor_groups" {
  for_each            = toset(var.editor_group_object_ids)
  app_role_id         = random_uuid.role_write.result
  principal_object_id = each.value
  resource_object_id  = azuread_service_principal.api.object_id
}

output "api_client_id" { value = azuread_application.api.client_id }
output "api_identifier_uri" { value = azuread_application_identifier_uri.api.identifier_uri }
output "api_scope" { value = "${azuread_application_identifier_uri.api.identifier_uri}/user_impersonation" }
output "api_service_principal_object_id" { value = azuread_service_principal.api.object_id }
output "web_client_id" { value = azuread_application.web.client_id }
output "role_ids" {
  value = { read = random_uuid.role_read.result, write = random_uuid.role_write.result }
}
