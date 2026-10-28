# Operations workbook (change operations-workbook, platform-observability spec): key indicators, latest errors and
# Azure Monitor metrics of the critical components on one page. Built here with jsonencode(); the KQL queries live in
# queries/*.kql and the filter lists in parameters/*.kql (no time filter: the workbook's time range applies; {Site},
# {DeviceType} and {DeviceId} are the filters) and are checked by scripts/check-workbook-queries.sh.

variable "resource_group_name" { type = string }
variable "location" { type = string }
variable "environment_name" { type = string }
variable "tenant_id" { type = string }
variable "resources" {
  description = "Resource ids shown by the workbook."
  type = object({
    application_insights = string
    postgres             = string
    iothub               = string
    eventhubs            = string
    servicebus           = string
    api_management       = string
  })
}
variable "tags" { type = map(string) }

locals {
  day_ms = 86400000
  appi   = lower(var.resources.application_insights)

  header = {
    type    = 1
    name    = "header"
    content = { json = "## Smart Device Platform — operations (${var.environment_name})" }
  }

  # Device-level filters (values listed from the decoder's per-message spans; DeviceId follows Site and DeviceType).
  filters = [
    { id = "6c1b1e6c-3f0e-4b8e-9a52-1f3a8c3e0d02", name = "Site", label = "Site" },
    { id = "6c1b1e6c-3f0e-4b8e-9a52-1f3a8c3e0d03", name = "DeviceType", label = "Device type" },
    { id = "6c1b1e6c-3f0e-4b8e-9a52-1f3a8c3e0d04", name = "DeviceId", label = "Device ID" },
  ]

  parameters = {
    type = 9
    name = "parameters"
    content = {
      version = "KqlParameterItem/1.0"
      style   = "pills"
      parameters = concat([{
        id         = "6c1b1e6c-3f0e-4b8e-9a52-1f3a8c3e0d01"
        version    = "KqlParameterItem/1.0"
        name       = "TimeRange"
        label      = "Time range"
        type       = 4
        isRequired = true
        value      = { durationMs = local.day_ms }
        typeSettings = {
          allowCustom      = true
          selectableValues = [for ms in [3600000, 14400000, 43200000, local.day_ms, 3 * local.day_ms, 7 * local.day_ms, 30 * local.day_ms] : { durationMs = ms }]
        }
        }], [for f in local.filters : {
        id                       = f.id
        version                  = "KqlParameterItem/1.0"
        name                     = f.name
        label                    = f.label
        type                     = 2
        isRequired               = false
        multiSelect              = true
        quote                    = "'"
        delimiter                = ","
        query                    = file("${path.module}/parameters/${f.name}.kql")
        value                    = ["value::all"]
        typeSettings             = { additionalResourceOptions = ["value::all"], selectAllValue = "*", showDefault = false }
        timeContextFromParameter = "TimeRange"
        timeContext              = { durationMs = local.day_ms }
        queryType                = 0
        resourceType             = "microsoft.insights/components"
        crossComponentResources  = [local.appi]
      }])
    }
  }

  # A KQL query on Application Insights, bound to the time range.
  kql = { for name, item in {
    key-indicators = {
      title = "", visualization = "tiles", size = 0, width = "100", extra = {
        tileSettings = {
          showBorder      = true
          titleContent    = { columnMatch = "Indicator", formatter = 1 }
          leftContent     = { columnMatch = "Value", formatter = 1 }
          subtitleContent = { columnMatch = "Detail", formatter = 1 }
        }
      }
    }
    ingestion-trend = { title = "Device messages per 15 minutes", visualization = "timechart", size = 0, width = "50", extra = {} }
    api-trend       = { title = "API requests and failures per 15 minutes", visualization = "timechart", size = 0, width = "50", extra = {} }
    latest-errors = {
      title = "Latest errors (exceptions, failed requests and dependencies)", visualization = "table", size = 0, width = "100", extra = {
        gridSettings = { rowLimit = 50, filter = true, formatters = [{ columnMatch = "Operation id", formatter = 1 }] }
      }
    }
    rejections = { title = "Device messages rejected per hour, by reason", visualization = "barchart", size = 0, width = "100", extra = {} }
    } : name => {
    type        = 3
    name        = name
    customWidth = item.width
    content = merge({
      version                  = "KqlItem/1.0"
      query                    = file("${path.module}/queries/${name}.kql")
      title                    = item.title
      size                     = item.size
      timeContextFromParameter = "TimeRange"
      timeContext              = { durationMs = local.day_ms }
      queryType                = 0
      resourceType             = "microsoft.insights/components"
      crossComponentResources  = [local.appi]
      visualization            = item.visualization
    }, item.extra)
  } }

  # Azure Monitor platform metrics of one resource, bound to the time range (aggregation: 1 total, 3 maximum, 4 average).
  metric_charts = [
    { name = "pg-load", title = "PostgreSQL CPU and memory (%)", type = "microsoft.dbforpostgresql/flexibleservers", id = var.resources.postgres, metrics = [["cpu_percent", 4], ["memory_percent", 4]] },
    { name = "pg-storage", title = "PostgreSQL storage (%) and active connections", type = "microsoft.dbforpostgresql/flexibleservers", id = var.resources.postgres, metrics = [["storage_percent", 4], ["active_connections", 4]] },
    { name = "iothub-devices", title = "IoT Hub connected devices", type = "microsoft.devices/iothubs", id = var.resources.iothub, metrics = [["connectedDeviceCount", 4]] },
    { name = "iothub-telemetry", title = "IoT Hub telemetry messages sent and throttling errors", type = "microsoft.devices/iothubs", id = var.resources.iothub, metrics = [["d2c.telemetry.ingress.success", 1], ["d2c.telemetry.ingress.sendThrottle", 1]] },
    { name = "eventhubs-messages", title = "Event Hubs incoming and outgoing messages", type = "microsoft.eventhub/namespaces", id = var.resources.eventhubs, metrics = [["IncomingMessages", 1], ["OutgoingMessages", 1]] },
    { name = "eventhubs-errors", title = "Event Hubs throttled requests and server errors", type = "microsoft.eventhub/namespaces", id = var.resources.eventhubs, metrics = [["ThrottledRequests", 1], ["ServerErrors", 1]] },
    { name = "servicebus-messages", title = "Service Bus active and dead-lettered messages (alerting)", type = "microsoft.servicebus/namespaces", id = var.resources.servicebus, metrics = [["ActiveMessages", 4], ["DeadletteredMessages", 4]] },
    { name = "apim-traffic", title = "API Management requests and duration (ms)", type = "microsoft.apimanagement/service", id = var.resources.api_management, metrics = [["Requests", 1], ["Duration", 4]] },
  ]

  metrics = [for c in local.metric_charts : {
    type        = 10
    name        = c.name
    customWidth = "50"
    content = {
      chartId                  = "sdp-${c.name}"
      version                  = "MetricsItem/2.0"
      size                     = 0
      chartType                = 2
      resourceType             = c.type
      metricScope              = 0
      resourceIds              = [lower(c.id)]
      timeContextFromParameter = "TimeRange"
      timeContext              = { durationMs = local.day_ms }
      metrics                  = [for m in c.metrics : { namespace = c.type, metric = "${c.type}--${m[0]}", aggregation = m[1] }]
      title                    = c.title
      gridSettings             = { rowLimit = 10000 }
    }
  }]

  section = { for name, text in {
    indicators = "Every tile, list and chart follows the time range. **Site, Device type and Device ID** filter the device figures: devices reporting, measurements stored and rejected, the messages trend and rejections. Ingestion latency follows the device type only. API (device-api and provisioning-api, health probes excluded), cache, alerting, errors and the Azure resource charts are platform-wide."
    errors     = "## Latest errors\nPlatform-wide, newest first. Copy an operation id into **Transaction search** of Application Insights to follow the whole transaction."
    components = "## Critical components\nPlatform-wide Azure Monitor metrics of the services that carry device data (IoT Hub → Event Hubs → PostgreSQL) and of alerting and the API gateway."
  } : name => { type = 1, name = "section-${name}", content = { json = text } } }

  workbook = {
    version = "Notebook/1.0"
    items = concat(
      [local.header, local.parameters, local.kql["key-indicators"], local.section.indicators],
      [local.kql["ingestion-trend"], local.kql["api-trend"], local.kql["rejections"]],
      [local.section.errors, local.kql["latest-errors"]],
      [local.section.components],
      local.metrics,
    )
    fallbackResourceIds = [local.appi]
    "$schema"           = "https://github.com/Microsoft/Application-Insights-Workbooks/blob/master/schema/workbook.json"
  }
}

resource "azurerm_application_insights_workbook" "operations" {
  # Deterministic name: re-provisioning updates the same workbook.
  name                = uuidv5("dns", "operations-workbook.${var.environment_name}.sdp")
  resource_group_name = var.resource_group_name
  location            = var.location
  display_name        = "Smart Device Platform — operations (${var.environment_name})"
  source_id           = local.appi
  category            = "workbook"
  data_json           = jsonencode(local.workbook)
  tags                = var.tags

  lifecycle {
    precondition {
      condition     = alltrue([for item in local.workbook.items : contains([3, 10], item.type) ? item.content.timeContextFromParameter == "TimeRange" : true])
      error_message = "Every query and metric chart of the operations workbook must follow the TimeRange parameter."
    }
    precondition {
      condition     = toset([for name in fileset("${path.module}/queries", "*.kql") : trimsuffix(name, ".kql")]) == toset(keys(local.kql))
      error_message = "Every query in modules/monitoring/queries must be shown by the workbook, and every workbook query must have its .kql file."
    }
    precondition {
      condition     = toset([for name in fileset("${path.module}/parameters", "*.kql") : trimsuffix(name, ".kql")]) == toset([for f in local.filters : f.name])
      error_message = "Every query in modules/monitoring/parameters must be a workbook filter, and every filter must have its .kql file."
    }
  }
}

output "workbook_id" { value = azurerm_application_insights_workbook.operations.id }
output "workbook_url" { value = "https://portal.azure.com/#@${var.tenant_id}/resource${azurerm_application_insights_workbook.operations.id}/workbook" }
