# Role assignments per workload (implementation guidelines §4, ADR-0005). Data-plane access only; no keys.

locals {
  workload_principals = {
    decoder    = module.identity.decoder.principal_id
    simulator  = module.identity.simulator.principal_id
    migrator   = module.identity.migrator.principal_id
    device_api = module.identity.device_api.principal_id
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
  ]
}
