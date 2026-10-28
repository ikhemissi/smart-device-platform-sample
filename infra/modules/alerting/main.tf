# Alerting (ADR-0009, M5 design D1/D2/D5): Service Bus topic of decoded measurements with a filtered subscription for
# the alert service, and Azure Communication Services Email for notifications. Entra authentication only.

variable "resource_group_name" { type = string }
variable "location" { type = string }
variable "environment_name" { type = string }
variable "token" { type = string }
variable "subscription_filter" {
  description = "SQL filter on message properties (siteId, deviceId, payloadFormat) selecting the monitored devices."
  type        = string
}
variable "tags" { type = map(string) }

resource "azurerm_servicebus_namespace" "main" {
  name                = "sbns-${var.environment_name}-${var.token}"
  resource_group_name = var.resource_group_name
  location            = var.location
  sku                 = "Standard"
  local_auth_enabled  = false
  minimum_tls_version = "1.2"
  tags                = var.tags
}

# Versioned integration contract: one message per persisted measurement (implementation guidelines §9).
resource "azurerm_servicebus_topic" "measurements" {
  name                                    = "measurements"
  namespace_id                            = azurerm_servicebus_namespace.main.id
  requires_duplicate_detection            = true
  duplicate_detection_history_time_window = "PT10M"
  default_message_ttl                     = "PT1H"
}

resource "azurerm_servicebus_subscription" "alerting" {
  name                                 = "alerting"
  topic_id                             = azurerm_servicebus_topic.measurements.id
  lock_duration                        = "PT1M"
  max_delivery_count                   = 10
  default_message_ttl                  = "PT1H"
  dead_lettering_on_message_expiration = true
}

# A subscription created through Azure Resource Manager has no rule (no `$Default` true-filter): it only receives the
# messages selected by this filter.
resource "azurerm_servicebus_subscription_rule" "monitored_devices" {
  name            = "monitored-devices"
  subscription_id = azurerm_servicebus_subscription.alerting.id
  filter_type     = "SqlFilter"
  sql_filter      = var.subscription_filter
}

# --- Email ------------------------------------------------------------------------------------------------------

resource "azurerm_email_communication_service" "main" {
  name                = "ecs-${var.environment_name}-${var.token}"
  resource_group_name = var.resource_group_name
  data_location       = "Europe"
  tags                = var.tags
}

resource "azurerm_email_communication_service_domain" "managed" {
  name              = "AzureManagedDomain"
  email_service_id  = azurerm_email_communication_service.main.id
  domain_management = "AzureManaged"
  tags              = var.tags
}

resource "azurerm_communication_service" "main" {
  name                = "acs-${var.environment_name}-${var.token}"
  resource_group_name = var.resource_group_name
  data_location       = "Europe"
  tags                = var.tags
}

resource "azurerm_communication_service_email_domain_association" "managed" {
  communication_service_id = azurerm_communication_service.main.id
  email_service_domain_id  = azurerm_email_communication_service_domain.managed.id
}

output "servicebus_namespace_id" { value = azurerm_servicebus_namespace.main.id }
output "servicebus_namespace_name" { value = azurerm_servicebus_namespace.main.name }
output "servicebus_namespace_fqdn" { value = "${azurerm_servicebus_namespace.main.name}.servicebus.windows.net" }
output "topic_id" { value = azurerm_servicebus_topic.measurements.id }
output "topic_name" { value = azurerm_servicebus_topic.measurements.name }
output "subscription_id" { value = azurerm_servicebus_subscription.alerting.id }
output "subscription_name" { value = azurerm_servicebus_subscription.alerting.name }
output "communication_service_id" { value = azurerm_communication_service.main.id }
output "communication_service_endpoint" { value = "https://${azurerm_communication_service.main.hostname}" }
output "sender_address" { value = "DoNotReply@${azurerm_email_communication_service_domain.managed.mail_from_sender_domain}" }
