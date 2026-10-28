# API Management: single public entry point for the platform APIs (ADR-0007, M2 design D3).

variable "resource_group_name" { type = string }
variable "location" { type = string }
variable "environment_name" { type = string }
variable "token" { type = string }
variable "sku_name" { type = string }
variable "tenant_id" { type = string }
variable "publisher_email" { type = string }
variable "application_insights" {
  type      = object({ id = string, connection_string = string })
  sensitive = true
}
variable "api_client_id" { type = string }
variable "api_identifier_uri" { type = string }
variable "allowed_origins" { type = list(string) }
variable "device_api_url" {
  description = "Base URL of the device API container app (https://<fqdn>)."
  type        = string
}
variable "openapi_file" {
  description = "OpenAPI document exported by the device API; the API is imported once it exists."
  type        = string
}
variable "provisioning_api_url" {
  description = "Base URL of the provisioning API container app (https://<fqdn>)."
  type        = string
}
variable "provisioning_openapi_file" {
  description = "OpenAPI document exported by the provisioning API; its operations are routed to that backend."
  type        = string
}
variable "rate_limit_calls_per_minute" {
  type    = number
  default = 600
}
variable "tags" { type = map(string) }

locals {
  base_path    = "/api/v1"
  methods      = ["get", "post", "put", "patch", "delete"]
  device_api   = fileexists(var.openapi_file) ? jsondecode(file(var.openapi_file)) : null
  provisioning = fileexists(var.provisioning_openapi_file) ? jsondecode(file(var.provisioning_openapi_file)) : null
  # One API (M4 design D6): both services' documents merged per path and method. The device API is the default
  # backend; provisioning operations are routed to their service by an operation-level policy.
  paths = {
    for path in distinct(concat(keys(try(local.device_api.paths, {})), keys(try(local.provisioning.paths, {})))) :
    path => merge(lookup(try(local.device_api.paths, {}), path, {}), lookup(try(local.provisioning.paths, {}), path, {}))
  }
  api_description = "Devices, telemetry history and site trust of the Smart Device Platform. Requires a Microsoft Entra ID access token for the platform API; operations declare the app role they need in x-sdp-required-role."
  openapi = local.device_api == null ? null : merge(local.device_api, {
    info  = merge(local.device_api.info, { title = "Smart Device Platform API", description = local.api_description })
    paths = local.paths
    components = merge(local.device_api.components, {
      schemas = merge(lookup(local.device_api.components, "schemas", {}), try(local.provisioning.components.schemas, {}))
    })
  })
  # Operations are imported relative to the API path (api/v1); the backends keep the full path.
  openapi_relative = local.openapi == null ? null : merge(local.openapi, {
    servers = [{ url = "${var.device_api_url}${local.base_path}" }]
    paths   = { for path, item in local.openapi.paths : trimprefix(path, local.base_path) => item }
  })
  operation_roles = local.openapi == null ? {} : merge([
    for path, item in local.openapi.paths : {
      for method, op in item : op.operationId => lookup(op, "x-sdp-required-role", "read")
      if contains(local.methods, method)
    }
  ]...)
  provisioning_operations = flatten([
    for path, item in try(local.provisioning.paths, {}) : [for method, op in item : op.operationId if contains(local.methods, method)]
  ])
}

resource "azurerm_api_management" "main" {
  name                = "apim-${var.environment_name}-${var.token}"
  resource_group_name = var.resource_group_name
  location            = var.location
  publisher_name      = "Smart Device Platform"
  publisher_email     = var.publisher_email
  sku_name            = var.sku_name
  tags                = var.tags

  identity {
    type = "SystemAssigned"
  }
}

# Entra-authenticated telemetry (Application Insights local auth is disabled, ADR-0005).
resource "azurerm_role_assignment" "apim_metrics_publisher" {
  scope                = var.application_insights.id
  role_definition_name = "Monitoring Metrics Publisher"
  principal_id         = azurerm_api_management.main.identity[0].principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_api_management_logger" "app_insights" {
  name                = "appinsights"
  api_management_name = azurerm_api_management.main.name
  resource_group_name = var.resource_group_name
  resource_id         = var.application_insights.id

  application_insights {
    connection_string  = var.application_insights.connection_string
    identity_client_id = "SystemAssigned"
  }

  depends_on = [azurerm_role_assignment.apim_metrics_publisher]
}

resource "azurerm_api_management_diagnostic" "app_insights" {
  identifier                = "applicationinsights"
  api_management_name       = azurerm_api_management.main.name
  resource_group_name       = var.resource_group_name
  api_management_logger_id  = azurerm_api_management_logger.app_insights.id
  sampling_percentage       = 100
  always_log_errors         = true
  verbosity                 = "information"
  http_correlation_protocol = "W3C"
  log_client_ip             = false
}

resource "azurerm_api_management_api" "platform" {
  count                 = local.openapi == null ? 0 : 1
  name                  = "sdp-api"
  api_management_name   = azurerm_api_management.main.name
  resource_group_name   = var.resource_group_name
  display_name          = "Smart Device Platform API"
  description           = local.api_description
  revision              = "1"
  path                  = trimprefix(local.base_path, "/")
  protocols             = ["https"]
  service_url           = "${var.device_api_url}${local.base_path}"
  subscription_required = false

  import {
    content_format = "openapi+json"
    content_value  = jsonencode(local.openapi_relative)
  }
}

resource "azurerm_api_management_api_policy" "platform" {
  count               = local.openapi == null ? 0 : 1
  api_name            = azurerm_api_management_api.platform[0].name
  api_management_name = azurerm_api_management.main.name
  resource_group_name = var.resource_group_name
  xml_content = templatefile("${path.module}/policies/api.xml.tftpl", {
    tenant_id          = var.tenant_id
    api_client_id      = var.api_client_id
    api_identifier_uri = var.api_identifier_uri
    allowed_origins    = var.allowed_origins
    rate_limit_calls   = var.rate_limit_calls_per_minute
  })
}

resource "azurerm_api_management_backend" "provisioning_api" {
  name                = "provisioning-api"
  api_management_name = azurerm_api_management.main.name
  resource_group_name = var.resource_group_name
  protocol            = "http"
  url                 = "${var.provisioning_api_url}${local.base_path}"
  description         = "Site trust: sites, site certificates, audit (M4)"
}

resource "azurerm_api_management_api_operation_policy" "role" {
  for_each = {
    for id, role in local.operation_roles : id => role
    if role != "authenticated" || contains(local.provisioning_operations, id)
  }
  api_name            = azurerm_api_management_api.platform[0].name
  api_management_name = azurerm_api_management.main.name
  resource_group_name = var.resource_group_name
  operation_id        = each.key
  xml_content = templatefile("${path.module}/policies/require-role.xml.tftpl", {
    backend_id    = contains(local.provisioning_operations, each.key) ? azurerm_api_management_backend.provisioning_api.name : ""
    required_role = each.value
    app_role      = each.value == "write" ? "DeviceData.Write" : "DeviceData.Read"
    detail        = each.value == "write" ? "The Edit device data permission (DeviceData.Write) is required." : "The Query device data permission (DeviceData.Read) is required."
  })
  depends_on = [azurerm_api_management_api_policy.platform, azurerm_api_management_backend.provisioning_api]
}

output "name" { value = azurerm_api_management.main.name }
output "id" { value = azurerm_api_management.main.id }
output "gateway_url" { value = azurerm_api_management.main.gateway_url }
output "api_base_url" { value = "${azurerm_api_management.main.gateway_url}${local.base_path}" }
output "api_imported" { value = local.openapi != null }
