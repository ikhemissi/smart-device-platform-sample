# Role assignments per workload (implementation guidelines §4, ADR-0005). Data-plane access only; no keys.

locals {
  workload_principals = {
    decoder          = module.identity.decoder.principal_id
    simulator        = module.identity.simulator.principal_id
    migrator         = module.identity.migrator.principal_id
    device_api       = module.identity.device_api.principal_id
    provisioning_api = module.identity.provisioning_api.principal_id
    alert_service    = module.identity.alert_service.principal_id
  }
}

resource "azurerm_role_assignment" "acr_pull" {
  scope                = module.core.container_registry_id
  role_definition_name = "AcrPull"
  principal_id         = module.identity.acr_pull.principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_role_assignment" "metrics_publisher" {
  for_each             = local.workload_principals
  scope                = module.core.application_insights_id
  role_definition_name = "Monitoring Metrics Publisher"
  principal_id         = each.value
  principal_type       = "ServicePrincipal"
}

resource "azurerm_role_assignment" "decoder_eventhub_receiver" {
  scope                = module.ingestion.event_hub_id
  role_definition_name = "Azure Event Hubs Data Receiver"
  principal_id         = module.identity.decoder.principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_role_assignment" "decoder_checkpoints" {
  scope                = module.ingestion.checkpoint_container_resource_id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = module.identity.decoder.principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_role_assignment" "simulator_site_cas" {
  scope                = module.core.key_vault_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = module.identity.simulator.principal_id
  principal_type       = "ServicePrincipal"
}

# Provisioning API (M4): enrollment groups (DPS data plane), CA certificates (DPS control plane, custom role limited to
# certificates) and device enable/disable (IoT Hub registry).
resource "azurerm_role_assignment" "provisioning_dps_data" {
  scope                = module.ingestion.dps_id
  role_definition_name = "Device Provisioning Service Data Contributor"
  principal_id         = module.identity.provisioning_api.principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_role_definition" "dps_certificates" {
  name        = "SDP DPS certificate manager (${var.environment_name})"
  scope       = module.ingestion.dps_id
  description = "Register, read and remove CA certificates of the platform's Device Provisioning Service."
  permissions {
    actions = [
      "Microsoft.Devices/provisioningServices/read",
      "Microsoft.Devices/provisioningServices/certificates/Read",
      "Microsoft.Devices/provisioningServices/certificates/Write",
      "Microsoft.Devices/provisioningServices/certificates/Delete",
    ]
  }
  assignable_scopes = [module.ingestion.dps_id]
}

resource "azurerm_role_assignment" "provisioning_dps_certificates" {
  scope              = module.ingestion.dps_id
  role_definition_id = azurerm_role_definition.dps_certificates.role_definition_resource_id
  principal_id       = module.identity.provisioning_api.principal_id
  principal_type     = "ServicePrincipal"
}

resource "azurerm_role_assignment" "provisioning_iothub_registry" {
  scope                = module.ingestion.iothub_id
  role_definition_name = "IoT Hub Registry Contributor"
  principal_id         = module.identity.provisioning_api.principal_id
  principal_type       = "ServicePrincipal"
}

# Alerting (M5): the decoder publishes to the topic; the alert service receives from its subscription only and sends
# emails through Communication Services with a custom role limited to email sending (no access keys).
resource "azurerm_role_assignment" "decoder_servicebus_sender" {
  scope                = module.alerting.topic_id
  role_definition_name = "Azure Service Bus Data Sender"
  principal_id         = module.identity.decoder.principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_role_assignment" "alert_service_servicebus_receiver" {
  scope                = module.alerting.subscription_id
  role_definition_name = "Azure Service Bus Data Receiver"
  principal_id         = module.identity.alert_service.principal_id
  principal_type       = "ServicePrincipal"
}

resource "azurerm_role_definition" "email_sender" {
  name        = "SDP email sender (${var.environment_name})"
  scope       = module.alerting.communication_service_id
  description = "Send emails through the platform's Communication Services resource with Microsoft Entra authentication."
  permissions {
    actions = [
      "Microsoft.Communication/CommunicationServices/Read",
      "Microsoft.Communication/CommunicationServices/Write",
      "Microsoft.Communication/EmailServices/write",
    ]
  }
  assignable_scopes = [module.alerting.communication_service_id]
}

resource "azurerm_role_assignment" "alert_service_email" {
  scope              = module.alerting.communication_service_id
  role_definition_id = azurerm_role_definition.email_sender.role_definition_resource_id
  principal_id       = module.identity.alert_service.principal_id
  principal_type     = "ServicePrincipal"
}

# Deployer: needed by the post-provision bootstrap (site CAs, enrollment groups) and for troubleshooting.
resource "azurerm_role_assignment" "deployer_key_vault" {
  scope                = module.core.key_vault_id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_role_assignment" "deployer_dps" {
  scope                = module.ingestion.dps_id
  role_definition_name = "Device Provisioning Service Data Contributor"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_role_assignment" "deployer_iothub" {
  scope                = module.ingestion.iothub_id
  role_definition_name = "IoT Hub Data Contributor"
  principal_id         = data.azurerm_client_config.current.object_id
}

# Deployer: runs the alerting services locally, sends test messages and test emails.
resource "azurerm_role_assignment" "deployer_servicebus_sender" {
  scope                = module.alerting.topic_id
  role_definition_name = "Azure Service Bus Data Sender"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_role_assignment" "deployer_servicebus_receiver" {
  scope                = module.alerting.subscription_id
  role_definition_name = "Azure Service Bus Data Receiver"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_role_assignment" "deployer_email" {
  scope              = module.alerting.communication_service_id
  role_definition_id = azurerm_role_definition.email_sender.role_definition_resource_id
  principal_id       = data.azurerm_client_config.current.object_id
}

resource "time_sleep" "rbac_propagation" {
  create_duration = "60s"
  depends_on = [
    azurerm_role_assignment.acr_pull,
    azurerm_role_assignment.metrics_publisher,
    azurerm_role_assignment.decoder_eventhub_receiver,
    azurerm_role_assignment.decoder_checkpoints,
    azurerm_role_assignment.simulator_site_cas,
    azurerm_role_assignment.deployer_key_vault,
    azurerm_role_assignment.deployer_dps,
    azurerm_role_assignment.deployer_iothub,
    azurerm_role_assignment.provisioning_dps_data,
    azurerm_role_assignment.provisioning_dps_certificates,
    azurerm_role_assignment.provisioning_iothub_registry,
    azurerm_role_assignment.decoder_servicebus_sender,
    azurerm_role_assignment.alert_service_servicebus_receiver,
    azurerm_role_assignment.alert_service_email,
  ]
}
