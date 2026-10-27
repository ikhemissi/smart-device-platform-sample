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
  }
  dps_id_scope       = module.ingestion.dps_id_scope
  key_vault_uri      = module.core.key_vault_uri
  decoder_image      = var.decoder_image
  simulator_image    = var.simulator_image
  simulator_settings = merge({ SDP_SIM_SITES = join(",", var.demo_sites) }, var.simulator_settings)
  tags               = local.tags

  # Workload identities must hold their roles before the apps start.
  depends_on = [time_sleep.rbac_propagation]
}
