"""The committed openapi.json (imported by API Management) matches the service's routes."""

import json
import re
from pathlib import Path

from .conftest import make_app

DOCUMENT = json.loads((Path(__file__).parent.parent / "openapi.json").read_text())


def test_document_matches_the_routes(store):
    generated = make_app(store).openapi()
    routes = {
        (re.sub(r"\{[^}]+\}", "{}", path), method, op["operationId"])
        for path, item in generated["paths"].items()
        for method, op in item.items()
    }
    documented = {
        (re.sub(r"\{[^}]+\}", "{}", path), method, op["operationId"])
        for path, item in DOCUMENT["paths"].items()
        for method, op in item.items()
    }
    assert documented == routes


def test_every_operation_requires_the_read_role():
    assert DOCUMENT["openapi"] == "3.0.1"
    for item in DOCUMENT["paths"].values():
        for op in item.values():
            assert op["x-sdp-required-role"] == "read"
