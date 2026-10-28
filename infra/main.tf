data "azurerm_client_config" "current" {}

data "azuread_user" "deployer" {
  count     = var.deployer_is_user ? 1 : 0
  object_id = data.azurerm_client_config.current.object_id
}

data "http" "deployer_ip" {
  count = var.allow_deployer_ip ? 1 : 0
  url   = "https://api.ipify.org"
}

locals {
  token = substr(sha1("${var.subscription_id}-${var.environment_name}"), 0, 8)
  # Alphanumeric prefix for resources that do not accept hyphens (storage, registry).
  compact = substr(replace(lower(var.environment_name), "/[^a-z0-9]/", ""), 0, 10)
  # Tenant governance (implementation guidelines §7): both tags are required on every resource of the
  # demonstrator; SecurityControl=Ignore keeps public endpoints (Entra-only access) on Storage and Key Vault.
  tags = {
    "azd-env-name"    = var.environment_name
    "SecurityControl" = "Ignore"
    "CostControl"     = "Ignore"
  }
}

resource "azurerm_resource_group" "main" {
  name     = "rg-${var.environment_name}"
  location = var.location
  tags     = local.tags
}

module "core" {
  source              = "./modules/core"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.location
  environment_name    = var.environment_name
  token               = local.token
  compact             = local.compact
  tenant_id           = data.azurerm_client_config.current.tenant_id
  tags                = local.tags
}

module "identity" {
  source              = "./modules/identity"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.location
  token               = local.token
  tags                = local.tags
}

module "ingestion" {
  source              = "./modules/ingestion"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.location
  environment_name    = var.environment_name
  token               = local.token
  compact             = local.compact
  tags                = local.tags
}

module "alerting" {
  source              = "./modules/alerting"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.location
  environment_name    = var.environment_name
  token               = local.token
  subscription_filter = coalesce(var.alerting_subscription_filter, "siteId = 'paris-01'")
  tags                = local.tags
}

module "data" {
  source              = "./modules/data"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.location
  environment_name    = var.environment_name
  token               = local.token
  tenant_id           = data.azurerm_client_config.current.tenant_id
  sku_name            = var.postgres_sku
  migrator_identity   = module.identity.migrator
  developer_admin = var.deployer_is_user ? {
    object_id           = data.azurerm_client_config.current.object_id
    user_principal_name = data.azuread_user.deployer[0].user_principal_name
  } : null
  developer_ip = var.allow_deployer_ip ? chomp(data.http.deployer_ip[0].response_body) : null
  tags         = local.tags
}

module "cache" {
  source              = "./modules/cache"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.location
  environment_name    = var.environment_name
  token               = local.token
  sku_name            = var.redis_sku
  high_availability   = var.redis_high_availability
  reader_principals = merge(
    { device_api = module.identity.device_api.principal_id },
    var.deployer_is_user ? { deployer = data.azurerm_client_config.current.object_id } : {},
  )
  tags = local.tags
}

module "web" {
  source              = "./modules/web"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.web_location
  environment_name    = var.environment_name
  token               = local.token
  tags                = local.tags
}

module "entra" {
  source                  = "./modules/entra"
  environment_name        = var.environment_name
  web_origin              = module.web.origin
  deployer_object_id      = var.deployer_is_user ? data.azurerm_client_config.current.object_id : null
  reader_group_object_ids = var.reader_group_object_ids
  editor_group_object_ids = var.editor_group_object_ids
  preauthorize_azure_cli  = var.preauthorize_azure_cli
}

module "api" {
  source              = "./modules/api"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.location
  environment_name    = var.environment_name
  token               = local.token
  sku_name            = var.apim_sku
  tenant_id           = data.azurerm_client_config.current.tenant_id
  publisher_email     = var.deployer_is_user ? data.azuread_user.deployer[0].user_principal_name : "noreply@example.com"
  application_insights = {
    id                = module.core.application_insights_id
    connection_string = module.core.application_insights_connection_string
  }
  api_client_id             = module.entra.api_client_id
  api_identifier_uri        = module.entra.api_identifier_uri
  allowed_origins           = [module.web.origin, "http://localhost:5173"]
  device_api_url            = module.apps.device_api_url
  openapi_file              = "${path.root}/../services/device-api/openapi.json"
  provisioning_api_url      = module.apps.provisioning_api_url
  provisioning_openapi_file = "${path.root}/../services/provisioning-api/openapi.json"
  tags                      = local.tags
}

module "apps" {
  source                       = "./modules/apps"
  resource_group_name          = azurerm_resource_group.main.name
  location                     = var.location
  environment_name             = var.environment_name
  token                        = local.token
  container_app_environment_id = module.core.container_app_environment_id
  registry_server              = module.core.container_registry_login_server
  identities                   = module.identity
  application_insights = {
    connection_string = module.core.application_insights_connection_string
  }
  postgres = {
    jdbc_url = "jdbc:postgresql://${module.data.postgres_fqdn}:5432/${module.data.database_name}?sslmode=require"
  }
  eventhubs = {
    namespace_name           = module.ingestion.eventhubs_namespace_name
    namespace_fqdn           = module.ingestion.eventhubs_namespace_fqdn
    event_hub_name           = module.ingestion.event_hub_name
    consumer_group           = module.ingestion.decoder_consumer_group
    checkpoint_account_name  = module.ingestion.checkpoint_storage_account_name
    checkpoint_container     = module.ingestion.checkpoint_container_name
    checkpoint_container_url = module.ingestion.checkpoint_container_url
    partition_count          = module.ingestion.event_hub_partition_count
  }
  dps_id_scope           = module.ingestion.dps_id_scope
  key_vault_uri          = module.core.key_vault_uri
  decoder_image          = var.decoder_image
  simulator_image        = var.simulator_image
  simulator_settings     = merge({ SDP_SIM_SITES = join(",", var.demo_sites) }, var.simulator_settings)
  device_api_image       = var.device_api_image
  provisioning_api_image = var.provisioning_api_image
  provisioning = {
    dps_resource_id  = module.ingestion.dps_id
    dps_service_host = module.ingestion.dps_service_host
    iothub_hostname  = module.ingestion.iothub_hostname
  }
  redis = {
    host = module.cache.hostname
    port = module.cache.port
  }
  alerting = {
    publish_enabled        = var.alerting_publish_enabled
    namespace_name         = module.alerting.servicebus_namespace_name
    namespace_fqdn         = module.alerting.servicebus_namespace_fqdn
    topic_name             = module.alerting.topic_name
    subscription_name      = module.alerting.subscription_name
    communication_endpoint = module.alerting.communication_service_endpoint
    sender_address         = module.alerting.sender_address
  }
  alert_service_image = var.alert_service_image
  portal_base_url     = module.web.origin
  entra = {
    tenant_id          = data.azurerm_client_config.current.tenant_id
    api_client_id      = module.entra.api_client_id
    api_identifier_uri = module.entra.api_identifier_uri
  }
  tags = local.tags

  # Workload identities must hold their roles before the apps start.
  depends_on = [time_sleep.rbac_propagation]
}

module "monitoring" {
  source              = "./modules/monitoring"
  resource_group_name = azurerm_resource_group.main.name
  location            = var.location
  environment_name    = var.environment_name
  tenant_id           = data.azurerm_client_config.current.tenant_id
  resources = {
    application_insights = module.core.application_insights_id
    postgres             = module.data.server_id
    iothub               = module.ingestion.iothub_id
    eventhubs            = module.ingestion.eventhubs_namespace_id
    servicebus           = module.alerting.servicebus_namespace_id
    api_management       = module.api.id
  }
  tags = local.tags
}
