# Static Web App hosting the portal (M2 design D5). Region: eastus2, as westeurope is blocked by tenant policy
# (ADR-0008). No built-in authentication and no linked backend: the SPA signs users in with MSAL and calls APIM.

variable "resource_group_name" { type = string }
variable "location" { type = string }
variable "environment_name" { type = string }
variable "token" { type = string }
variable "tags" { type = map(string) }

resource "azurerm_static_web_app" "portal" {
  name                = "stapp-${var.environment_name}-${var.token}"
  resource_group_name = var.resource_group_name
  location            = var.location
  sku_tier            = "Free"
  sku_size            = "Free"
  tags                = merge(var.tags, { "azd-service-name" = "web" })
}

output "name" { value = azurerm_static_web_app.portal.name }
output "default_host_name" { value = azurerm_static_web_app.portal.default_host_name }
output "origin" { value = "https://${azurerm_static_web_app.portal.default_host_name}" }
