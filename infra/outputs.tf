# Non-secret values only (ADR-0005); azd stores them in the environment (azd env get-values).

output "AZURE_LOCATION" {
  value = var.location
}

output "AZURE_RESOURCE_GROUP" {
  value = azurerm_resource_group.main.name
}

output "AZURE_CONTAINER_REGISTRY_ENDPOINT" {
  value = module.core.container_registry_login_server
}

output "AZURE_CONTAINER_REGISTRY_NAME" {
  value = module.core.container_registry_name
}

output "AZURE_CONTAINER_APPS_ENVIRONMENT_NAME" {
  value = module.core.container_app_environment_name
}

output "KEY_VAULT_NAME" {
  value = module.core.key_vault_name
}

output "KEY_VAULT_URI" {
  value = module.core.key_vault_uri
}

output "APPLICATIONINSIGHTS_NAME" {
  value = module.core.application_insights_name
}

output "POSTGRES_HOST" {
  value = module.data.postgres_fqdn
}

output "POSTGRES_DATABASE" {
  value = module.data.database_name
}

output "DECODER_IDENTITY_NAME" {
  value = module.identity.decoder.name
}

output "DECODER_IDENTITY_PRINCIPAL_ID" {
  value = module.identity.decoder.principal_id
}

output "IOTHUB_NAME" {
  value = module.ingestion.iothub_name
}

output "DPS_NAME" {
  value = module.ingestion.dps_name
}

output "DPS_ID_SCOPE" {
  value = module.ingestion.dps_id_scope
}

output "EVENTHUBS_NAMESPACE" {
  value = module.ingestion.eventhubs_namespace_fqdn
}

output "MIGRATOR_JOB_NAME" {
  value = module.apps.migrator_job_name
}

output "MAINTENANCE_JOB_NAME" {
  value = module.apps.maintenance_job_name
}

output "DECODER_APP_NAME" {
  value = module.apps.decoder_app_name
}

output "SIMULATOR_APP_NAME" {
  value = module.apps.simulator_app_name
}

output "DEMO_SITES" {
  value = join(",", var.demo_sites)
}

output "DEVICE_API_APP_NAME" {
  value = module.apps.device_api_app_name
}

output "DEVICE_API_IDENTITY_NAME" {
  value = module.identity.device_api.name
}

output "DEVICE_API_IDENTITY_PRINCIPAL_ID" {
  value = module.identity.device_api.principal_id
}

output "APIM_NAME" {
  value = module.api.name
}

output "API_BASE_URL" {
  value = module.api.api_base_url
}

output "ENTRA_TENANT_ID" {
  value = data.azurerm_client_config.current.tenant_id
}

output "API_CLIENT_ID" {
  value = module.entra.api_client_id
}

output "API_SCOPE" {
  value = module.entra.api_scope
}

output "WEB_CLIENT_ID" {
  value = module.entra.web_client_id
}

output "WEB_URL" {
  value = module.web.origin
}

output "STATIC_WEB_APP_NAME" {
  value = module.web.name
}

output "REDIS_NAME" {
  value = module.cache.name
}

output "REDIS_HOST" {
  value = module.cache.hostname
}

output "PROVISIONING_API_APP_NAME" {
  value = module.apps.provisioning_api_app_name
}

output "PROVISIONING_API_IDENTITY_NAME" {
  value = module.identity.provisioning_api.name
}

output "DPS_SERVICE_HOST" {
  value = module.ingestion.dps_service_host
}

output "IOTHUB_HOSTNAME" {
  value = module.ingestion.iothub_hostname
}

output "ALERT_SERVICE_APP_NAME" {
  value = module.apps.alert_service_app_name
}

output "ALERT_SERVICE_IDENTITY_NAME" {
  value = module.identity.alert_service.name
}

output "SERVICEBUS_NAMESPACE" {
  value = module.alerting.servicebus_namespace_name
}

output "SERVICEBUS_NAMESPACE_FQDN" {
  value = module.alerting.servicebus_namespace_fqdn
}

output "COMMUNICATION_SERVICE_ENDPOINT" {
  value = module.alerting.communication_service_endpoint
}

output "EMAIL_SENDER_ADDRESS" {
  value = module.alerting.sender_address
}
