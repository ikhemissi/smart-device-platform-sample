# Device connectivity and telemetry stream (ADR-0002, ADR-0006): IoT Hub routes device messages with their site
# to a dedicated Event Hub using its managed identity; DPS provisions devices into the hub.

variable "resource_group_name" { type = string }
variable "location" { type = string }
variable "environment_name" { type = string }
variable "token" { type = string }
variable "compact" { type = string }
variable "tags" { type = map(string) }

variable "partition_count" {
  description = "Event Hub partitions (fixed at creation on Standard tier); bounds decoder parallelism."
  type        = number
  default     = 4
}

# --- Event Hubs ---------------------------------------------------------------------------------------------

resource "azurerm_eventhub_namespace" "main" {
  name                         = "evhns-${var.environment_name}-${var.token}"
  resource_group_name          = var.resource_group_name
  location                     = var.location
  sku                          = "Standard"
  capacity                     = 1
  local_authentication_enabled = false
  minimum_tls_version          = "1.2"
  tags                         = var.tags
}

resource "azurerm_eventhub" "telemetry" {
  name              = "device-telemetry"
  namespace_id      = azurerm_eventhub_namespace.main.id
  partition_count   = var.partition_count
  message_retention = 1
}

resource "azurerm_eventhub_consumer_group" "decoder" {
  name                = "telemetry-decoder"
  namespace_name      = azurerm_eventhub_namespace.main.name
  eventhub_name       = azurerm_eventhub.telemetry.name
  resource_group_name = var.resource_group_name
}

# --- Checkpoint storage (Entra only) ------------------------------------------------------------------------

resource "azurerm_storage_account" "checkpoints" {
  name                            = "st${var.compact}${var.token}"
  resource_group_name             = var.resource_group_name
  location                        = var.location
  account_tier                    = "Standard"
  account_replication_type        = "LRS"
  shared_access_key_enabled       = false
  allow_nested_items_to_be_public = false
  min_tls_version                 = "TLS1_2"
  default_to_oauth_authentication = true
  public_network_access_enabled   = true
  tags                            = var.tags
}

resource "azurerm_storage_container" "checkpoints" {
  name                  = "checkpoints"
  storage_account_id    = azurerm_storage_account.checkpoints.id
  container_access_type = "private"
}

# --- IoT Hub -----------------------------------------------------------------------------------------------

resource "azurerm_iothub" "main" {
  name                = "iot-${var.environment_name}-${var.token}"
  resource_group_name = var.resource_group_name
  location            = var.location
  # Kept enabled only for the DPS link, which cannot use Entra (ADR-0005 exception); no workload uses SAS.
  local_authentication_enabled = true
  event_hub_partition_count    = 4
  tags                         = var.tags

  sku {
    name     = "S1"
    capacity = 1
  }

  identity {
    type = "SystemAssigned"
  }

  lifecycle {
    # Routes, endpoints and enrichments are managed by their own resources below.
    ignore_changes = [route, endpoint, enrichment, fallback_route]
  }
}

resource "azurerm_role_assignment" "iothub_eventhub_sender" {
  scope                = azurerm_eventhub.telemetry.id
  role_definition_name = "Azure Event Hubs Data Sender"
  principal_id         = azurerm_iothub.main.identity[0].principal_id
  principal_type       = "ServicePrincipal"
}

# IoT Hub validates its permission on the Event Hub when the endpoint is created.
resource "time_sleep" "iothub_role_propagation" {
  create_duration = "90s"
  depends_on      = [azurerm_role_assignment.iothub_eventhub_sender]
}

resource "azurerm_iothub_endpoint_eventhub" "telemetry" {
  name                = "device-telemetry"
  resource_group_name = var.resource_group_name
  iothub_id           = azurerm_iothub.main.id
  authentication_type = "identityBased"
  endpoint_uri        = "sb://${azurerm_eventhub_namespace.main.name}.servicebus.windows.net"
  entity_path         = azurerm_eventhub.telemetry.name
  depends_on          = [time_sleep.iothub_role_propagation]
}

resource "azurerm_iothub_route" "telemetry" {
  name                = "device-telemetry"
  resource_group_name = var.resource_group_name
  iothub_name         = azurerm_iothub.main.name
  source              = "DeviceMessages"
  condition           = "true"
  endpoint_names      = [azurerm_iothub_endpoint_eventhub.telemetry.name]
  enabled             = true
}

resource "azurerm_iothub_enrichment" "site" {
  key                 = "siteId"
  value               = "$twin.tags.siteId"
  resource_group_name = var.resource_group_name
  iothub_name         = azurerm_iothub.main.name
  endpoint_names      = [azurerm_iothub_endpoint_eventhub.telemetry.name]
}

# Messages matching no route are dropped (the fallback to the built-in endpoint is disabled).
resource "azurerm_iothub_fallback_route" "disabled" {
  resource_group_name = var.resource_group_name
  iothub_name         = azurerm_iothub.main.name
  enabled             = false
  endpoint_names      = [azurerm_iothub_endpoint_eventhub.telemetry.name]
  source              = "DeviceMessages"
  condition           = "true"
}

# --- Device Provisioning Service ---------------------------------------------------------------------------

resource "azurerm_iothub_shared_access_policy" "dps_link" {
  name                = "dps-link"
  resource_group_name = var.resource_group_name
  iothub_name         = azurerm_iothub.main.name
  registry_read       = true
  registry_write      = true
  service_connect     = true
  device_connect      = true
}

resource "azurerm_iothub_dps" "main" {
  name                = "provs-${var.environment_name}-${var.token}"
  resource_group_name = var.resource_group_name
  location            = var.location
  allocation_policy   = "Hashed"
  tags                = var.tags

  sku {
    name     = "S1"
    capacity = 1
  }

  linked_hub {
    # Platform limitation: DPS links to IoT Hub with a shared access policy (ADR-0005 exception). The value is
    # passed resource to resource and is never output.
    connection_string = azurerm_iothub_shared_access_policy.dps_link.primary_connection_string
    location          = var.location
  }
}

output "eventhubs_namespace_name" { value = azurerm_eventhub_namespace.main.name }
output "eventhubs_namespace_fqdn" { value = "${azurerm_eventhub_namespace.main.name}.servicebus.windows.net" }
output "event_hub_name" { value = azurerm_eventhub.telemetry.name }
output "event_hub_id" { value = azurerm_eventhub.telemetry.id }
output "event_hub_partition_count" { value = azurerm_eventhub.telemetry.partition_count }
output "decoder_consumer_group" { value = azurerm_eventhub_consumer_group.decoder.name }
output "checkpoint_storage_account_name" { value = azurerm_storage_account.checkpoints.name }
output "checkpoint_container_name" { value = azurerm_storage_container.checkpoints.name }
output "checkpoint_container_url" { value = "${azurerm_storage_account.checkpoints.primary_blob_endpoint}${azurerm_storage_container.checkpoints.name}" }
output "checkpoint_container_resource_id" { value = "${azurerm_storage_account.checkpoints.id}/blobServices/default/containers/${azurerm_storage_container.checkpoints.name}" }
output "iothub_id" { value = azurerm_iothub.main.id }
output "iothub_name" { value = azurerm_iothub.main.name }
output "dps_id" { value = azurerm_iothub_dps.main.id }
output "dps_name" { value = azurerm_iothub_dps.main.name }
output "dps_id_scope" { value = azurerm_iothub_dps.main.id_scope }
