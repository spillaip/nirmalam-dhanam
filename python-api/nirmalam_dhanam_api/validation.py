from __future__ import annotations

from typing import Any

from .models import NdfValidationError


def require(value: bool, message: str) -> None:
    if not value:
        raise NdfValidationError(message)


def require_type(data: dict[str, Any], key: str, expected_type: type | tuple[type, ...]) -> Any:
    require(key in data, f"Missing required field: {key}")
    value = data[key]
    require(isinstance(value, expected_type), f"Field {key} must be {expected_type}, got {type(value).__name__}")
    return value


def require_literal(data: dict[str, Any], key: str, expected: Any) -> Any:
    value = require_type(data, key, type(expected))
    require(value == expected, f"Field {key} must be {expected!r}, got {value!r}")
    return value
