# Azure Managed Redis cache for history queries (M3 design D2). Entra-only: access keys are disabled and identities
# get the default access policy through access-policy assignments (ADR-0005).

variable "resource_group_name" { type = string }
variable "location" { type = string }
variable "environment_name" { type = string }
variable "token" { type = string }
variable "sku_name" { type = string }
variable "high_availability" { type = bool }
variable "reader_principals" {
  description = "Object ids granted data access (workload identities, and the deployer for local tooling in dev)."
  type        = map(string)
}
variable "tags" { type = map(string) }

resource "azurerm_managed_redis" "main" {
  name                      = "amr-${var.environment_name}-${var.token}"
  resource_group_name       = var.resource_group_name
  location                  = var.location
  sku_name                  = var.sku_name
  high_availability_enabled = var.high_availability
  public_network_access     = "Enabled"
  tags                      = var.tags

  default_database {
    access_keys_authentication_enabled = false
    client_protocol                    = "Encrypted"
    # Single endpoint: clients need no cluster awareness.
    clustering_policy = "EnterpriseCluster"
    # Pure cache: every key may be evicted under memory pressure.
    eviction_policy = "AllKeysLRU"
  }
}

resource "azurerm_managed_redis_access_policy_assignment" "data" {
  for_each         = var.reader_principals
  managed_redis_id = azurerm_managed_redis.main.id
  object_id        = each.value
}

output "name" { value = azurerm_managed_redis.main.name }
output "hostname" { value = azurerm_managed_redis.main.hostname }
output "port" { value = 10000 }
