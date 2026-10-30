variable "environment_name" {
  description = "azd environment name; prefixes resource names and is set as the azd-env-name tag."
  type        = string
}

variable "subscription_id" {
  description = "Target subscription (azd AZURE_SUBSCRIPTION_ID)."
  type        = string
}

variable "location" {
  description = "Primary region for all regional resources (ADR-0008)."
  type        = string
  default     = "francecentral"
}

variable "demo_sites" {
  description = "Demo sites prepared by the post-provision bootstrap (CA in Key Vault + DPS enrollment group)."
  type        = list(string)
  default     = ["paris-01", "lyon-01", "marseille-01"]
}

variable "deployer_is_user" {
  description = "True when the deploying principal is a user (adds it as PostgreSQL Entra administrator for local tooling, dev only)."
  type        = bool
  default     = true
}

variable "allow_deployer_ip" {
  description = "Open the PostgreSQL firewall to the deployer's public IP (local tooling, dev only)."
  type        = bool
  default     = true
}

variable "decoder_image" {
  description = "Telemetry decoder image deployed by azd (SERVICE_TELEMETRY_DECODER_IMAGE_NAME); empty on the first run."
  type        = string
  default     = ""
}

variable "simulator_image" {
  description = "Device simulator image deployed by azd (SERVICE_DEVICE_SIMULATOR_IMAGE_NAME); empty on the first run."
  type        = string
  default     = ""
}

variable "simulator_settings" {
  description = "Simulator configuration (environment variables without secrets), e.g. { SDP_SIM_INTERVAL = \"10s\" }."
  type        = map(string)
  default     = {}
}

variable "postgres_sku" {
  type    = string
  default = "B_Standard_B2s"
}

variable "device_api_image" {
  description = "Device API image deployed by azd (SERVICE_DEVICE_API_IMAGE_NAME); empty on the first run."
  type        = string
  default     = ""
}

variable "web_location" {
  description = "Static Web Apps region (westeurope, its only European region, is blocked by tenant policy)."
  type        = string
  default     = "eastus2"
}

variable "apim_sku" {
  description = "API Management v2 tier. Basic v2 covers every feature the platform uses (Entra token validation, CORS, rate limiting, managed-identity logging, response streaming); Standard v2 is only needed for VNet integration with network-isolated backends or inbound private endpoints."
  type        = string
  default     = "BasicV2_1"
  validation {
    condition     = can(regex("^(BasicV2|StandardV2)_[1-9][0-9]?$", var.apim_sku))
    error_message = "apim_sku must be BasicV2_<units> or StandardV2_<units>."
  }
}

variable "reader_group_object_ids" {
  description = "Entra groups granted DeviceData.Read."
  type        = list(string)
  default     = []
}

variable "editor_group_object_ids" {
  description = "Entra groups granted DeviceData.Write."
  type        = list(string)
  default     = []
}

variable "preauthorize_azure_cli" {
  description = "Pre-authorize the Azure CLI on the API scope (az account get-access-token for testing, dev only)."
  type        = bool
  default     = true
}

variable "redis_sku" {
  description = "Azure Managed Redis SKU for the history cache (Balanced_B0 is the smallest)."
  type        = string
  default     = "Balanced_B0"
}

variable "redis_high_availability" {
  description = "Replicate the cache (doubles cost); not needed for a cache that can be rebuilt from the database."
  type        = bool
  default     = false
}

variable "provisioning_api_image" {
  description = "Provisioning API image deployed by azd (SERVICE_PROVISIONING_API_IMAGE_NAME); empty on the first run."
  type        = string
  default     = ""
}

variable "alert_service_image" {
  description = "Alert service image deployed by azd (SERVICE_ALERT_SERVICE_IMAGE_NAME); empty on the first run."
  type        = string
  default     = ""
}

variable "device_analyst_image" {
  description = "AI device analyst image deployed by azd (SERVICE_DEVICE_ANALYST_IMAGE_NAME); empty on the first run."
  type        = string
  default     = ""
}

variable "alerting_subscription_filter" {
  description = "Service Bus SQL filter selecting the devices monitored for alerts (properties siteId, deviceId, payloadFormat), e.g. \"siteId IN ('paris-01','lyon-01')\"; set with `azd env set ALERTING_SUBSCRIPTION_FILTER`. Empty: siteId = 'paris-01'."
  type        = string
  default     = ""
}

variable "alerting_publish_enabled" {
  description = "Whether the decoder publishes persisted measurements to the Service Bus topic (ALERTING_PUBLISH_ENABLED)."
  type        = bool
  default     = true
}

# AI device analyst model (M6). Set with `azd env set AGENT_MODEL_*`; empty values fall back to the defaults below,
# which scripts/check-model-quota.sh (pre-provision) mirrors.
variable "agent_model_name" {
  description = "Foundry chat model (AGENT_MODEL_NAME). Empty: gpt-6-luna."
  type        = string
  default     = ""
}

variable "agent_model_version" {
  description = "Model version (AGENT_MODEL_VERSION). Empty: 2026-09-22."
  type        = string
  default     = ""
}

variable "agent_model_sku" {
  description = "Deployment SKU (AGENT_MODEL_SKU); DataZoneStandard keeps processing in the EU data zone. Empty: DataZoneStandard."
  type        = string
  default     = ""
}

variable "agent_model_capacity" {
  description = "Capacity in thousands of tokens per minute, bounded by the subscription quota (AGENT_MODEL_CAPACITY). Empty: 50."
  type        = string
  default     = ""
}

variable "agent_capture_content" {
  description = "Record prompts, answers and tool results of the AI device analyst in traces (AGENT_CAPTURE_CONTENT, \"true\" for demos). Empty: false."
  type        = string
  default     = ""
}

# Anomaly detection (M7). Set with `azd env set ANOMALY_*` / DECISION_MODEL_CAPACITY; empty values fall back to the
# defaults in main.tf, which scripts/check-model-quota.sh (pre-provision) mirrors.
variable "anomaly_detection_enabled" {
  description = "Deploy Microsoft-Decision-1 and the anomaly detector job (ANOMALY_DETECTION_ENABLED, \"false\" to switch off). Empty: true."
  type        = string
  default     = ""
}

variable "anomaly_sites" {
  description = "Comma-separated sites analysed by the anomaly detector (ANOMALY_SITES). Empty: paris-01."
  type        = string
  default     = ""
}

variable "anomaly_metrics" {
  description = "Comma-separated metrics analysed by the anomaly detector (ANOMALY_METRICS). Empty: temperature,humidity,pressure,co2,illuminance."
  type        = string
  default     = ""
}

variable "anomaly_deviation" {
  description = "Deviation score (in spreads from the usual value) that makes a reading a candidate (ANOMALY_DEVIATION). Empty: see main.tf."
  type        = string
  default     = ""
}

variable "anomaly_confidence" {
  description = "Probability from which Microsoft-Decision-1's classification is applied without review (ANOMALY_CONFIDENCE). Empty: see main.tf."
  type        = string
  default     = ""
}

variable "decision_model_capacity" {
  description = "Microsoft-Decision-1 capacity units, each 1 request and 1,000 tokens per minute (DECISION_MODEL_CAPACITY). Empty: 30."
  type        = string
  default     = ""
}

# Predictive maintenance (M9). Set with `azd env set MAINTENANCE_*`; empty values fall back to the defaults in main.tf.
variable "maintenance_enabled" {
  description = "Run the maintenance job (battery forecasts and maintenance queue) (MAINTENANCE_ENABLED, \"false\" to switch off). Empty: true."
  type        = string
  default     = ""
}

variable "maintenance_sites" {
  description = "Comma-separated sites assessed by the maintenance job (MAINTENANCE_SITES). Empty: every site."
  type        = string
  default     = ""
}

variable "maintenance_confidence" {
  description = "Intervention probability from which a device is queued (MAINTENANCE_CONFIDENCE). Empty: 0.5."
  type        = string
  default     = ""
}

variable "simulator_aging_devices" {
  description = "Comma-separated simulated devices whose battery drains in about two weeks (SIMULATOR_AGING_DEVICES). Empty: <site>-ruuvi5-003 for each demo site."
  type        = string
  default     = ""
}

# Site digests (M8). Set with `azd env set DIGEST_*`; empty values fall back to the defaults in main.tf.
variable "digest_enabled" {
  description = "Generate daily site digests (DIGEST_ENABLED, \"false\" to switch off). Empty: true."
  type        = string
  default     = ""
}

variable "digest_sites" {
  description = "Comma-separated sites that get a daily digest (DIGEST_SITES). Empty: every site."
  type        = string
  default     = ""
}

variable "digest_metrics" {
  description = "Comma-separated metrics summarised in digests (DIGEST_METRICS). Empty: see main.tf."
  type        = string
  default     = ""
}
