# Container apps and jobs (M1 design D5/D6/D7). Images are deployed by azd (apps) and by the post-provision hook
# (jobs); Terraform owns everything else. Health probes are enabled once a real image has been deployed.

variable "resource_group_name" { type = string }
variable "location" { type = string }
variable "environment_name" { type = string }
variable "token" { type = string }
variable "container_app_environment_id" { type = string }
variable "registry_server" { type = string }
variable "identities" {
  type = object({
    decoder          = object({ id = string, client_id = string, principal_id = string, name = string })
    simulator        = object({ id = string, client_id = string, principal_id = string, name = string })
    migrator         = object({ id = string, client_id = string, principal_id = string, name = string })
    acr_pull         = object({ id = string, client_id = string, principal_id = string, name = string })
    device_api       = object({ id = string, client_id = string, principal_id = string, name = string })
    provisioning_api = object({ id = string, client_id = string, principal_id = string, name = string })
    alert_service    = object({ id = string, client_id = string, principal_id = string, name = string })
    device_analyst   = object({ id = string, client_id = string, principal_id = string, name = string })
    anomaly_detector = object({ id = string, client_id = string, principal_id = string, name = string })
  })
}
variable "application_insights" {
  type      = object({ connection_string = string })
  sensitive = true
}
variable "postgres" { type = object({ jdbc_url = string, host = string, database = string }) }
variable "eventhubs" {
  type = object({
    namespace_name           = string
    namespace_fqdn           = string
    event_hub_name           = string
    consumer_group           = string
    checkpoint_account_name  = string
    checkpoint_container     = string
    checkpoint_container_url = string
    partition_count          = number
  })
}
variable "dps_id_scope" { type = string }
variable "key_vault_uri" { type = string }
variable "decoder_image" { type = string }
variable "simulator_image" { type = string }
variable "simulator_settings" { type = map(string) }
variable "device_api_image" { type = string }
variable "provisioning_api_image" { type = string }
variable "provisioning" {
  description = "DPS and IoT Hub endpoints managed by the provisioning API."
  type        = object({ dps_resource_id = string, dps_service_host = string, iothub_hostname = string })
}
variable "redis" { type = object({ host = string, port = number }) }
variable "alerting" {
  description = "Service Bus topic/subscription of decoded measurements and the email service (ADR-0009)."
  type = object({
    publish_enabled        = bool
    namespace_name         = string
    namespace_fqdn         = string
    topic_name             = string
    subscription_name      = string
    communication_endpoint = string
    sender_address         = string
  })
}
variable "alert_service_image" { type = string }
variable "portal_base_url" { type = string }
variable "device_analyst_image" { type = string }
variable "foundry" {
  type = object({ project_endpoint = string, openai_endpoint = string, model_deployment_name = string, capture_content = bool })
}
variable "api_base_url" {
  description = "Platform API base URL at the gateway; the agent's tools call it with the caller's token."
  type        = string
}
variable "entra" {
  description = "Token validation settings of the platform API (ADR-0007)."
  type        = object({ tenant_id = string, api_client_id = string, api_identifier_uri = string })
}
variable "anomaly_detection" {
  description = "Anomaly detector job settings (M7 design D6/D11); the job exists only when enabled."
  type = object({
    enabled             = bool
    sites               = string
    metrics             = string
    deviation           = string
    confidence          = string
    decision_endpoint   = string
    decision_deployment = string
  })
}
variable "tags" { type = map(string) }

locals {
  placeholder_image = "mcr.microsoft.com/k8se/quickstart:latest"

  # Shared by all Java workloads: OpenTelemetry export with Entra authentication (ADR-0005, guidelines §5).
  observability_env = {
    APPLICATIONINSIGHTS_CONNECTION_STRING = var.application_insights.connection_string
    OTEL_RESOURCE_ATTRIBUTES              = "deployment.environment=${var.environment_name}"
    LOGGING_STRUCTURED_FORMAT_CONSOLE     = "ecs"
  }

  decoder_env = merge(local.observability_env, {
    AZURE_CLIENT_ID                           = var.identities.decoder.client_id
    APPLICATIONINSIGHTS_AUTHENTICATION_STRING = "Authorization=AAD;ClientId=${var.identities.decoder.client_id}"
    APPLICATIONINSIGHTS_ROLE_NAME             = "sdp-telemetry-decoder"
    SDP_EVENTHUBS_NAMESPACE                   = var.eventhubs.namespace_fqdn
    SDP_EVENTHUB_NAME                         = var.eventhubs.event_hub_name
    SDP_EVENTHUBS_CONSUMER_GROUP              = var.eventhubs.consumer_group
    SDP_CHECKPOINT_CONTAINER_URL              = var.eventhubs.checkpoint_container_url
    SDP_DB_URL                                = var.postgres.jdbc_url
    SDP_DB_USERNAME                           = var.identities.decoder.name
    ALERTING_PUBLISH_ENABLED                  = tostring(var.alerting.publish_enabled)
    SDP_SERVICEBUS_NAMESPACE                  = var.alerting.namespace_fqdn
    SDP_SERVICEBUS_TOPIC                      = var.alerting.topic_name
  })

  simulator_env = merge(local.observability_env, {
    AZURE_CLIENT_ID                           = var.identities.simulator.client_id
    APPLICATIONINSIGHTS_AUTHENTICATION_STRING = "Authorization=AAD;ClientId=${var.identities.simulator.client_id}"
    APPLICATIONINSIGHTS_ROLE_NAME             = "sdp-device-simulator"
    SDP_DPS_ID_SCOPE                          = var.dps_id_scope
    SDP_KEYVAULT_URI                          = var.key_vault_uri
  }, var.simulator_settings)

  migrator_env = merge(local.observability_env, {
    AZURE_CLIENT_ID                           = var.identities.migrator.client_id
    APPLICATIONINSIGHTS_AUTHENTICATION_STRING = "Authorization=AAD;ClientId=${var.identities.migrator.client_id}"
    APPLICATIONINSIGHTS_ROLE_NAME             = "sdp-db-migrator"
    SDP_DB_URL                                = var.postgres.jdbc_url
    SDP_DB_USERNAME                           = var.identities.migrator.name
    SDP_DECODER_PRINCIPAL_NAME                = var.identities.decoder.name
    SDP_DECODER_PRINCIPAL_OBJECT_ID           = var.identities.decoder.principal_id
    SDP_DEVICE_API_PRINCIPAL_NAME             = var.identities.device_api.name
    SDP_DEVICE_API_PRINCIPAL_OBJECT_ID        = var.identities.device_api.principal_id
    SDP_PROVISIONING_API_PRINCIPAL_NAME       = var.identities.provisioning_api.name
    SDP_PROVISIONING_API_PRINCIPAL_OBJECT_ID  = var.identities.provisioning_api.principal_id
    SDP_ALERT_SERVICE_PRINCIPAL_NAME          = var.identities.alert_service.name
    SDP_ALERT_SERVICE_PRINCIPAL_OBJECT_ID     = var.identities.alert_service.principal_id
    SDP_ANOMALY_DETECTOR_PRINCIPAL_NAME       = var.identities.anomaly_detector.name
    SDP_ANOMALY_DETECTOR_PRINCIPAL_OBJECT_ID  = var.identities.anomaly_detector.principal_id
  })

  device_api_env = merge(local.observability_env, {
    AZURE_CLIENT_ID                           = var.identities.device_api.client_id
    APPLICATIONINSIGHTS_AUTHENTICATION_STRING = "Authorization=AAD;ClientId=${var.identities.device_api.client_id}"
    APPLICATIONINSIGHTS_ROLE_NAME             = "sdp-device-api"
    SDP_DB_URL                                = var.postgres.jdbc_url
    SDP_DB_USERNAME                           = var.identities.device_api.name
    SDP_ENTRA_TENANT_ID                       = var.entra.tenant_id
    SDP_API_AUDIENCES                         = "${var.entra.api_client_id},${var.entra.api_identifier_uri}"
    SDP_EVENTHUB_PARTITIONS                   = tostring(var.eventhubs.partition_count)
    SDP_REDIS_HOST                            = var.redis.host
    SDP_REDIS_PORT                            = tostring(var.redis.port)
  })

  provisioning_api_env = merge(local.observability_env, {
    AZURE_CLIENT_ID                           = var.identities.provisioning_api.client_id
    APPLICATIONINSIGHTS_AUTHENTICATION_STRING = "Authorization=AAD;ClientId=${var.identities.provisioning_api.client_id}"
    APPLICATIONINSIGHTS_ROLE_NAME             = "sdp-provisioning-api"
    SDP_DB_URL                                = var.postgres.jdbc_url
    SDP_DB_USERNAME                           = var.identities.provisioning_api.name
    SDP_ENTRA_TENANT_ID                       = var.entra.tenant_id
    SDP_API_AUDIENCES                         = "${var.entra.api_client_id},${var.entra.api_identifier_uri}"
    SDP_DPS_RESOURCE_ID                       = var.provisioning.dps_resource_id
    SDP_DPS_HOST                              = var.provisioning.dps_service_host
    SDP_IOTHUB_HOST                           = var.provisioning.iothub_hostname
  })

  alert_service_env = merge(local.observability_env, {
    AZURE_CLIENT_ID                           = var.identities.alert_service.client_id
    APPLICATIONINSIGHTS_AUTHENTICATION_STRING = "Authorization=AAD;ClientId=${var.identities.alert_service.client_id}"
    APPLICATIONINSIGHTS_ROLE_NAME             = "sdp-alert-service"
    SDP_DB_URL                                = var.postgres.jdbc_url
    SDP_DB_USERNAME                           = var.identities.alert_service.name
    SDP_SERVICEBUS_NAMESPACE                  = var.alerting.namespace_fqdn
    SDP_SERVICEBUS_TOPIC                      = var.alerting.topic_name
    SDP_SERVICEBUS_SUBSCRIPTION               = var.alerting.subscription_name
    SDP_EMAIL_ENDPOINT                        = var.alerting.communication_endpoint
    SDP_EMAIL_SENDER                          = var.alerting.sender_address
    SDP_PORTAL_BASE_URL                       = var.portal_base_url
  })

  # Python agent (M6 design D1/D7): the Azure Monitor distro takes the credential in code, so no authentication string.
  device_analyst_env = {
    APPLICATIONINSIGHTS_CONNECTION_STRING = var.application_insights.connection_string
    OTEL_RESOURCE_ATTRIBUTES              = "deployment.environment=${var.environment_name}"
    OTEL_SERVICE_NAME                     = "sdp-device-analyst"
    AZURE_CLIENT_ID                       = var.identities.device_analyst.client_id
    FOUNDRY_PROJECT_ENDPOINT              = var.foundry.project_endpoint
    AZURE_OPENAI_ENDPOINT                 = var.foundry.openai_endpoint
    FOUNDRY_MODEL_DEPLOYMENT_NAME         = var.foundry.model_deployment_name
    SDP_ENTRA_TENANT_ID                   = var.entra.tenant_id
    SDP_API_AUDIENCES                     = "${var.entra.api_client_id},${var.entra.api_identifier_uri}"
    SDP_API_BASE_URL                      = var.api_base_url
    SDP_REDIS_HOST                        = var.redis.host
    SDP_REDIS_PORT                        = tostring(var.redis.port)
    SDP_AGENT_CAPTURE_CONTENT             = tostring(var.foundry.capture_content)
  }

  # Python job (M7 design D1/D10): PostgreSQL with an Entra token as password, models with the workload identity.
  anomaly_detector_env = {
    APPLICATIONINSIGHTS_CONNECTION_STRING = var.application_insights.connection_string
    OTEL_RESOURCE_ATTRIBUTES              = "deployment.environment=${var.environment_name}"
    OTEL_SERVICE_NAME                     = "sdp-anomaly-detector"
    AZURE_CLIENT_ID                       = var.identities.anomaly_detector.client_id
    SDP_DB_HOST                           = var.postgres.host
    SDP_DB_NAME                           = var.postgres.database
    SDP_DB_USER                           = var.identities.anomaly_detector.name
    AZURE_OPENAI_ENDPOINT                 = var.foundry.openai_endpoint
    FOUNDRY_MODEL_DEPLOYMENT_NAME         = var.foundry.model_deployment_name
    SDP_DECISION_ENDPOINT                 = var.anomaly_detection.decision_endpoint
    SDP_DECISION_DEPLOYMENT               = var.anomaly_detection.decision_deployment
    SDP_ANOMALY_SITES                     = var.anomaly_detection.sites
    SDP_ANOMALY_METRICS                   = var.anomaly_detection.metrics
    SDP_ANOMALY_DEVIATION                 = var.anomaly_detection.deviation
    SDP_ANOMALY_CONFIDENCE                = var.anomaly_detection.confidence
  }
}

# --- Telemetry decoder ----------------------------------------------------------------------------------------

resource "azurerm_container_app" "decoder" {
  name                         = "ca-decoder-${var.token}"
  resource_group_name          = var.resource_group_name
  container_app_environment_id = var.container_app_environment_id
  revision_mode                = "Single"
  workload_profile_name        = "Consumption"
  tags                         = merge(var.tags, { "azd-service-name" = "telemetry-decoder" })

  identity {
    type         = "UserAssigned"
    identity_ids = [var.identities.decoder.id, var.identities.acr_pull.id]
  }

  registry {
    server   = var.registry_server
    identity = var.identities.acr_pull.id
  }

  template {
    min_replicas = 1
    max_replicas = 4

    container {
      name   = "telemetry-decoder"
      image  = var.decoder_image != "" ? var.decoder_image : local.placeholder_image
      cpu    = 1
      memory = "2Gi"

      dynamic "env" {
        for_each = local.decoder_env
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "liveness_probe" {
        for_each = var.decoder_image != "" ? [1] : []
        content {
          transport               = "HTTP"
          port                    = 8080
          path                    = "/actuator/health/liveness"
          initial_delay           = 30
          interval_seconds        = 10
          failure_count_threshold = 5
        }
      }

      dynamic "readiness_probe" {
        for_each = var.decoder_image != "" ? [1] : []
        content {
          transport               = "HTTP"
          port                    = 8080
          path                    = "/actuator/health/readiness"
          initial_delay           = 20
          interval_seconds        = 10
          failure_count_threshold = 6
        }
      }
    }

    # Scale on unprocessed events with the decoder's managed identity (KEDA azure-eventhub scaler).
    custom_scale_rule {
      name             = "eventhub-backlog"
      custom_rule_type = "azure-eventhub"
      identity_id      = var.identities.decoder.id
      metadata = {
        eventHubNamespace         = var.eventhubs.namespace_name
        eventHubName              = var.eventhubs.event_hub_name
        consumerGroup             = var.eventhubs.consumer_group
        storageAccountName        = var.eventhubs.checkpoint_account_name
        blobContainer             = var.eventhubs.checkpoint_container
        checkpointStrategy        = "blobMetadata"
        unprocessedEventThreshold = "500"
      }
    }
  }

  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }
}

# --- Device simulator -----------------------------------------------------------------------------------------

resource "azurerm_container_app" "simulator" {
  name                         = "ca-simulator-${var.token}"
  resource_group_name          = var.resource_group_name
  container_app_environment_id = var.container_app_environment_id
  revision_mode                = "Single"
  workload_profile_name        = "Consumption"
  tags                         = merge(var.tags, { "azd-service-name" = "device-simulator" })

  identity {
    type         = "UserAssigned"
    identity_ids = [var.identities.simulator.id, var.identities.acr_pull.id]
  }

  registry {
    server   = var.registry_server
    identity = var.identities.acr_pull.id
  }

  template {
    min_replicas = 1
    max_replicas = 1

    container {
      name   = "device-simulator"
      image  = var.simulator_image != "" ? var.simulator_image : local.placeholder_image
      cpu    = 0.5
      memory = "1Gi"

      dynamic "env" {
        for_each = local.simulator_env
        content {
          name  = env.key
          value = env.value
        }
      }
    }
  }

  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }
}

# --- Device API ------------------------------------------------------------------------------------------------

resource "azurerm_container_app" "device_api" {
  name                         = "ca-device-api-${var.token}"
  resource_group_name          = var.resource_group_name
  container_app_environment_id = var.container_app_environment_id
  revision_mode                = "Single"
  workload_profile_name        = "Consumption"
  tags                         = merge(var.tags, { "azd-service-name" = "device-api" })

  identity {
    type         = "UserAssigned"
    identity_ids = [var.identities.device_api.id, var.identities.acr_pull.id]
  }

  registry {
    server   = var.registry_server
    identity = var.identities.acr_pull.id
  }

  # External ingress: APIM v2 reaches it without a VNet (ADR-0008); the service validates tokens itself (ADR-0007).
  ingress {
    external_enabled = true
    target_port      = var.device_api_image != "" ? 8080 : 80
    transport        = "auto"
    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }

  template {
    min_replicas = 1
    max_replicas = 3

    container {
      name   = "device-api"
      image  = var.device_api_image != "" ? var.device_api_image : local.placeholder_image
      cpu    = 0.5
      memory = "1Gi"

      dynamic "env" {
        for_each = local.device_api_env
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "liveness_probe" {
        for_each = var.device_api_image != "" ? [1] : []
        content {
          transport               = "HTTP"
          port                    = 8080
          path                    = "/actuator/health/liveness"
          initial_delay           = 30
          interval_seconds        = 10
          failure_count_threshold = 5
        }
      }

      dynamic "readiness_probe" {
        for_each = var.device_api_image != "" ? [1] : []
        content {
          transport               = "HTTP"
          port                    = 8080
          path                    = "/actuator/health/readiness"
          initial_delay           = 15
          interval_seconds        = 10
          failure_count_threshold = 6
        }
      }
    }

    http_scale_rule {
      name                = "http"
      concurrent_requests = "50"
    }
  }

  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }
}

# --- Provisioning API (site certificates, M4) --------------------------------------------------------------------

resource "azurerm_container_app" "provisioning_api" {
  name                         = "ca-prov-api-${var.token}"
  resource_group_name          = var.resource_group_name
  container_app_environment_id = var.container_app_environment_id
  revision_mode                = "Single"
  workload_profile_name        = "Consumption"
  tags                         = merge(var.tags, { "azd-service-name" = "provisioning-api" })

  identity {
    type         = "UserAssigned"
    identity_ids = [var.identities.provisioning_api.id, var.identities.acr_pull.id]
  }

  registry {
    server   = var.registry_server
    identity = var.identities.acr_pull.id
  }

  # External ingress: APIM v2 reaches it without a VNet (ADR-0008); the service validates tokens itself (ADR-0007).
  ingress {
    external_enabled = true
    target_port      = var.provisioning_api_image != "" ? 8080 : 80
    transport        = "auto"
    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }

  template {
    # One replica: reconciliation and device operations are serialised by an advisory lock anyway; editing trust is rare.
    min_replicas = 1
    max_replicas = 2

    container {
      name   = "provisioning-api"
      image  = var.provisioning_api_image != "" ? var.provisioning_api_image : local.placeholder_image
      cpu    = 0.5
      memory = "1Gi"

      dynamic "env" {
        for_each = local.provisioning_api_env
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "liveness_probe" {
        for_each = var.provisioning_api_image != "" ? [1] : []
        content {
          transport               = "HTTP"
          port                    = 8080
          path                    = "/actuator/health/liveness"
          initial_delay           = 30
          interval_seconds        = 10
          failure_count_threshold = 5
        }
      }

      dynamic "readiness_probe" {
        for_each = var.provisioning_api_image != "" ? [1] : []
        content {
          transport               = "HTTP"
          port                    = 8080
          path                    = "/actuator/health/readiness"
          initial_delay           = 15
          interval_seconds        = 10
          failure_count_threshold = 6
        }
      }
    }

    http_scale_rule {
      name                = "http"
      concurrent_requests = "50"
    }
  }

  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }
}

# --- Alert service ---------------------------------------------------------------------------------------------

resource "azurerm_container_app" "alert_service" {
  name                         = "ca-alerts-${var.token}"
  resource_group_name          = var.resource_group_name
  container_app_environment_id = var.container_app_environment_id
  revision_mode                = "Single"
  workload_profile_name        = "Consumption"
  tags                         = merge(var.tags, { "azd-service-name" = "alert-service" })

  identity {
    type         = "UserAssigned"
    identity_ids = [var.identities.alert_service.id, var.identities.acr_pull.id]
  }

  registry {
    server   = var.registry_server
    identity = var.identities.acr_pull.id
  }

  template {
    # One replica always runs: besides consuming measurements, it sends pending notifications and reminders.
    min_replicas = 1
    max_replicas = 3

    container {
      name   = "alert-service"
      image  = var.alert_service_image != "" ? var.alert_service_image : local.placeholder_image
      cpu    = 0.5
      memory = "1Gi"

      dynamic "env" {
        for_each = local.alert_service_env
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "liveness_probe" {
        for_each = var.alert_service_image != "" ? [1] : []
        content {
          transport               = "HTTP"
          port                    = 8080
          path                    = "/actuator/health/liveness"
          initial_delay           = 30
          interval_seconds        = 10
          failure_count_threshold = 5
        }
      }

      dynamic "readiness_probe" {
        for_each = var.alert_service_image != "" ? [1] : []
        content {
          transport               = "HTTP"
          port                    = 8080
          path                    = "/actuator/health/readiness"
          initial_delay           = 15
          interval_seconds        = 10
          failure_count_threshold = 6
        }
      }
    }

    # Scale on the subscription's backlog with the service's managed identity (KEDA azure-servicebus scaler).
    custom_scale_rule {
      name             = "servicebus-backlog"
      custom_rule_type = "azure-servicebus"
      identity_id      = var.identities.alert_service.id
      metadata = {
        namespace        = var.alerting.namespace_name
        topicName        = var.alerting.topic_name
        subscriptionName = var.alerting.subscription_name
        messageCount     = "200"
      }
    }
  }

  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }
}

# --- AI device analyst (M6) -------------------------------------------------------------------------------------

resource "azurerm_container_app" "device_analyst" {
  name                         = "ca-analyst-${var.token}"
  resource_group_name          = var.resource_group_name
  container_app_environment_id = var.container_app_environment_id
  revision_mode                = "Single"
  workload_profile_name        = "Consumption"
  tags                         = merge(var.tags, { "azd-service-name" = "device-analyst" })

  identity {
    type         = "UserAssigned"
    identity_ids = [var.identities.device_analyst.id, var.identities.acr_pull.id]
  }

  registry {
    server   = var.registry_server
    identity = var.identities.acr_pull.id
  }

  # External ingress: APIM v2 reaches it without a VNet (ADR-0008); the service validates tokens itself (ADR-0007).
  ingress {
    external_enabled = true
    target_port      = var.device_analyst_image != "" ? 8080 : 80
    transport        = "auto"
    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }

  template {
    # One warm replica keeps the first answer of a demo fast.
    min_replicas = 1
    max_replicas = 3

    container {
      name   = "device-analyst"
      image  = var.device_analyst_image != "" ? var.device_analyst_image : local.placeholder_image
      cpu    = 0.5
      memory = "1Gi"

      dynamic "env" {
        for_each = local.device_analyst_env
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "liveness_probe" {
        for_each = var.device_analyst_image != "" ? [1] : []
        content {
          transport               = "HTTP"
          port                    = 8080
          path                    = "/health/live"
          initial_delay           = 10
          interval_seconds        = 10
          failure_count_threshold = 5
        }
      }

      dynamic "readiness_probe" {
        for_each = var.device_analyst_image != "" ? [1] : []
        content {
          transport               = "HTTP"
          port                    = 8080
          path                    = "/health/ready"
          initial_delay           = 5
          interval_seconds        = 10
          failure_count_threshold = 6
        }
      }
    }

    # Conversations stream for up to 120 s, so few concurrent requests per replica.
    http_scale_rule {
      name                = "http"
      concurrent_requests = "20"
    }
  }

  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }
}

# --- Database migrator jobs -------------------------------------------------------------------------------------

resource "azurerm_container_app_job" "migrate" {
  name                         = "caj-migrate-${var.token}"
  resource_group_name          = var.resource_group_name
  location                     = var.location
  container_app_environment_id = var.container_app_environment_id
  workload_profile_name        = "Consumption"
  replica_timeout_in_seconds   = 1800
  replica_retry_limit          = 1
  tags                         = var.tags

  manual_trigger_config {
    parallelism              = 1
    replica_completion_count = 1
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [var.identities.migrator.id, var.identities.acr_pull.id]
  }

  registry {
    server   = var.registry_server
    identity = var.identities.acr_pull.id
  }

  template {
    container {
      name   = "db-migrator"
      image  = local.placeholder_image
      cpu    = 0.5
      memory = "1Gi"

      dynamic "env" {
        for_each = merge(local.migrator_env, { SDP_MIGRATOR_TASKS = "migrate,maintain" })
        content {
          name  = env.key
          value = env.value
        }
      }
    }
  }

  lifecycle {
    # Set by the post-provision hook (image built in ACR).
    ignore_changes = [template[0].container[0].image]
  }
}

resource "azurerm_container_app_job" "maintain" {
  name                         = "caj-maint-${var.token}"
  resource_group_name          = var.resource_group_name
  location                     = var.location
  container_app_environment_id = var.container_app_environment_id
  workload_profile_name        = "Consumption"
  replica_timeout_in_seconds   = 1800
  replica_retry_limit          = 1
  tags                         = var.tags

  schedule_trigger_config {
    cron_expression          = "0 * * * *"
    parallelism              = 1
    replica_completion_count = 1
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [var.identities.migrator.id, var.identities.acr_pull.id]
  }

  registry {
    server   = var.registry_server
    identity = var.identities.acr_pull.id
  }

  template {
    container {
      name   = "db-migrator"
      image  = local.placeholder_image
      cpu    = 0.5
      memory = "1Gi"

      dynamic "env" {
        for_each = merge(local.migrator_env, { SDP_MIGRATOR_TASKS = "maintain" })
        content {
          name  = env.key
          value = env.value
        }
      }
    }
  }

  lifecycle {
    ignore_changes = [template[0].container[0].image]
  }
}

# --- Anomaly detector job (M7) ----------------------------------------------------------------------------------

resource "azurerm_container_app_job" "anomaly_detector" {
  count                        = var.anomaly_detection.enabled ? 1 : 0
  name                         = "caj-anomaly-${var.token}"
  resource_group_name          = var.resource_group_name
  location                     = var.location
  container_app_environment_id = var.container_app_environment_id
  workload_profile_name        = "Consumption"
  # One cycle per run; a failed or late cycle is retried by the next one (design D1).
  replica_timeout_in_seconds = 240
  replica_retry_limit        = 0
  tags                       = var.tags

  schedule_trigger_config {
    cron_expression          = "*/5 * * * *"
    parallelism              = 1
    replica_completion_count = 1
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [var.identities.anomaly_detector.id, var.identities.acr_pull.id]
  }

  registry {
    server   = var.registry_server
    identity = var.identities.acr_pull.id
  }

  template {
    container {
      name   = "anomaly-detector"
      image  = local.placeholder_image
      cpu    = 0.5
      memory = "1Gi"

      dynamic "env" {
        for_each = local.anomaly_detector_env
        content {
          name  = env.key
          value = env.value
        }
      }
    }
  }

  lifecycle {
    # Set by the post-provision hook (image built in ACR).
    ignore_changes = [template[0].container[0].image]
  }
}

output "decoder_app_name" { value = azurerm_container_app.decoder.name }
output "simulator_app_name" { value = azurerm_container_app.simulator.name }
output "migrator_job_name" { value = azurerm_container_app_job.migrate.name }
output "device_api_app_name" { value = azurerm_container_app.device_api.name }
output "device_api_url" { value = "https://${azurerm_container_app.device_api.ingress[0].fqdn}" }
output "provisioning_api_app_name" { value = azurerm_container_app.provisioning_api.name }
output "provisioning_api_url" { value = "https://${azurerm_container_app.provisioning_api.ingress[0].fqdn}" }
output "maintenance_job_name" { value = azurerm_container_app_job.maintain.name }
output "alert_service_app_name" { value = azurerm_container_app.alert_service.name }
output "device_analyst_app_name" { value = azurerm_container_app.device_analyst.name }
output "anomaly_detector_job_name" { value = join("", azurerm_container_app_job.anomaly_detector[*].name) }
output "device_analyst_url" { value = "https://${azurerm_container_app.device_analyst.ingress[0].fqdn}" }
