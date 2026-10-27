# PostgreSQL Flexible Server, Entra-only (ADR-0004, ADR-0005). The migrator identity is the Entra administrator and
# schema owner; workload principals are created by the migrator (afterMigrate.sql).

variable "resource_group_name" { type = string }
variable "location" { type = string }
variable "environment_name" { type = string }
variable "token" { type = string }
variable "tenant_id" { type = string }
variable "sku_name" { type = string }
variable "migrator_identity" {
  type = object({ name = string, principal_id = string })
}
variable "developer_admin" {
  description = "Deploying user added as Entra administrator for local tooling (dev only); null to skip."
  type        = object({ object_id = string, user_principal_name = string })
  default     = null
}
variable "developer_ip" {
  type    = string
  default = null
}
variable "tags" { type = map(string) }

resource "azurerm_postgresql_flexible_server" "main" {
  name                          = "psql-${var.environment_name}-${var.token}"
  resource_group_name           = var.resource_group_name
  location                      = var.location
  version                       = "18"
  sku_name                      = var.sku_name
  storage_mb                    = 32768
  backup_retention_days         = 7
  geo_redundant_backup_enabled  = false
  public_network_access_enabled = true
  tags                          = var.tags

  authentication {
    active_directory_auth_enabled = true
    password_auth_enabled         = false
    tenant_id                     = var.tenant_id
  }

  lifecycle {
    ignore_changes = [zone]
  }
}

resource "azurerm_postgresql_flexible_server_database" "sdp" {
  name      = "sdp"
  server_id = azurerm_postgresql_flexible_server.main.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

resource "azurerm_postgresql_flexible_server_configuration" "extensions" {
  name      = "azure.extensions"
  server_id = azurerm_postgresql_flexible_server.main.id
  value     = "PG_PARTMAN"
}

resource "azurerm_postgresql_flexible_server_active_directory_administrator" "migrator" {
  server_name         = azurerm_postgresql_flexible_server.main.name
  resource_group_name = var.resource_group_name
  tenant_id           = var.tenant_id
  object_id           = var.migrator_identity.principal_id
  principal_name      = var.migrator_identity.name
  principal_type      = "ServicePrincipal"
}

resource "azurerm_postgresql_flexible_server_active_directory_administrator" "developer" {
  count               = var.developer_admin == null ? 0 : 1
  server_name         = azurerm_postgresql_flexible_server.main.name
  resource_group_name = var.resource_group_name
  tenant_id           = var.tenant_id
  object_id           = var.developer_admin.object_id
  principal_name      = var.developer_admin.user_principal_name
  principal_type      = "User"
  # Entra administrators cannot be added concurrently.
  depends_on = [azurerm_postgresql_flexible_server_active_directory_administrator.migrator]
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "azure_services" {
  name             = "allow-azure-services"
  server_id        = azurerm_postgresql_flexible_server.main.id
  start_ip_address = "0.0.0.0"
  end_ip_address   = "0.0.0.0"
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "developer" {
  count            = var.developer_ip == null ? 0 : 1
  name             = "allow-deployer"
  server_id        = azurerm_postgresql_flexible_server.main.id
  start_ip_address = var.developer_ip
  end_ip_address   = var.developer_ip
}

output "postgres_fqdn" { value = azurerm_postgresql_flexible_server.main.fqdn }
output "database_name" { value = azurerm_postgresql_flexible_server_database.sdp.name }
output "server_name" { value = azurerm_postgresql_flexible_server.main.name }
