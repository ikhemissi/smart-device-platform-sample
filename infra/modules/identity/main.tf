# One user-assigned managed identity per workload, plus a dedicated image-pull identity (ADR-0005).

variable "resource_group_name" { type = string }
variable "location" { type = string }
variable "token" { type = string }
variable "tags" { type = map(string) }

locals {
  workloads = ["decoder", "simulator", "migrator", "acr-pull"]
}

resource "azurerm_user_assigned_identity" "workload" {
  for_each            = toset(local.workloads)
  name                = "id-${each.key}-${var.token}"
  resource_group_name = var.resource_group_name
  location            = var.location
  tags                = var.tags
}

output "decoder" { value = azurerm_user_assigned_identity.workload["decoder"] }
output "simulator" { value = azurerm_user_assigned_identity.workload["simulator"] }
output "migrator" { value = azurerm_user_assigned_identity.workload["migrator"] }
output "acr_pull" { value = azurerm_user_assigned_identity.workload["acr-pull"] }
