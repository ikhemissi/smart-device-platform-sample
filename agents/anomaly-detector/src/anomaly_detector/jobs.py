"""What the job entry points share: logging and the Entra credential (the workload identity in Azure, developer tools
locally)."""

import logging
from collections.abc import Callable

from azure.core.credentials import TokenCredential
from azure.identity import DefaultAzureCredential, get_bearer_token_provider

COGNITIVE_SCOPE = "https://cognitiveservices.azure.com/.default"
# Locally, credentials come from developer tools (Azure CLI): allow more than the default 10 s on a busy machine.
DEVELOPER_TOOL_TIMEOUT = 60


def configure_logging() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    logging.getLogger("azure").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)


def credential() -> DefaultAzureCredential:
    return DefaultAzureCredential(process_timeout=DEVELOPER_TOOL_TIMEOUT)


def model_token(credential: TokenCredential) -> Callable[[], str]:
    """Bearer tokens for the Foundry models (the decision API and the chat model)."""
    return get_bearer_token_provider(credential, COGNITIVE_SCOPE)
