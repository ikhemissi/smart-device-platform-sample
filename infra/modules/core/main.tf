# Observability, registry, secrets and the Container Apps environment (M1 design D7).

variable "resource_group_name" { type = string }
variable "location" { type = string }
variable "environment_name" { type = string }
variable "token" { type = string }
variable "compact" { type = string }
variable "tenant_id" { type = string }
variable "tags" { type = map(string) }

resource "azurerm_log_analytics_workspace" "main" {
  name                = "log-${var.environment_name}-${var.token}"
  resource_group_name = var.resource_group_name
  location            = var.location
  sku                 = "PerGB2018"
  retention_in_days   = 90
  tags                = var.tags

  lifecycle {
    # Holds the demonstrator's history (shown to customers): a change forcing replacement must fail, not wipe it.
    prevent_destroy = true
  }
}

resource "azurerm_application_insights" "main" {
  name                         = "appi-${var.environment_name}-${var.token}"
  resource_group_name          = var.resource_group_name
  location                     = var.location
  workspace_id                 = azurerm_log_analytics_workspace.main.id
  application_type             = "java"
  local_authentication_enabled = false
  tags                         = var.tags

  lifecycle {
    # Holds the demonstrator's history (shown to customers): a change forcing replacement must fail, not wipe it.
    prevent_destroy = true
  }
}

resource "azurerm_container_registry" "main" {
  name                   = "cr${var.compact}${var.token}"
  resource_group_name    = var.resource_group_name
  location               = var.location
  sku                    = "Basic"
  admin_enabled          = false
  anonymous_pull_enabled = false
  tags                   = var.tags
}

resource "azurerm_key_vault" "main" {
  name                          = "kv-${substr(var.environment_name, 0, 10)}-${var.token}"
  resource_group_name           = var.resource_group_name
  location                      = var.location
  tenant_id                     = var.tenant_id
  sku_name                      = "standard"
  rbac_authorization_enabled    = true
  purge_protection_enabled      = false
  soft_delete_retention_days    = 7
  public_network_access_enabled = true
  tags                          = var.tags

  lifecycle {
    # Holds the site CAs trusted by devices: recreating it would regenerate them and lock devices out.
    prevent_destroy = true
  }
}

resource "azurerm_container_app_environment" "main" {
  name                = "cae-${var.environment_name}-${var.token}"
  resource_group_name = var.resource_group_name
  location            = var.location
  # Logs flow through Azure Monitor diagnostic settings: no Log Analytics shared key involved.
  logs_destination = "azure-monitor"
  tags             = var.tags

  workload_profile {
    name                  = "Consumption"
    workload_profile_type = "Consumption"
  }
}

resource "azurerm_monitor_diagnostic_setting" "container_apps" {
  name                       = "logs-to-workspace"
  target_resource_id         = azurerm_container_app_environment.main.id
  log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id

  enabled_log {
    category_group = "allLogs"
  }
}

output "log_analytics_workspace_id" { value = azurerm_log_analytics_workspace.main.id }
output "application_insights_id" { value = azurerm_application_insights.main.id }
output "application_insights_name" { value = azurerm_application_insights.main.name }
# With local authentication disabled the connection string only locates the resource; ingestion requires Entra.
output "application_insights_connection_string" {
  value     = azurerm_application_insights.main.connection_string
  sensitive = true
}
output "container_registry_id" { value = azurerm_container_registry.main.id }
output "container_registry_name" { value = azurerm_container_registry.main.name }
output "container_registry_login_server" { value = azurerm_container_registry.main.login_server }
output "key_vault_id" { value = azurerm_key_vault.main.id }
output "key_vault_name" { value = azurerm_key_vault.main.name }
output "key_vault_uri" { value = azurerm_key_vault.main.vault_uri }
output "container_app_environment_id" { value = azurerm_container_app_environment.main.id }
output "container_app_environment_name" { value = azurerm_container_app_environment.main.name }
