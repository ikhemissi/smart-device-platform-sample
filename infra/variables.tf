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
