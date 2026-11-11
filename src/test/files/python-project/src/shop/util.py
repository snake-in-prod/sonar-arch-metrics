"""Helpers."""
import os

_CACHE = {}
_unused_flag = True
_a, (_b, c) = 1, (2, 3)
__all__ = ["visible"]


def visible(x):
    """Doc."""
    assert x
    if x > 1:
        for i in range(x):
            while i:
                i -= 1
    elif x < 0:
        return [y for y in range(3) if y]
    else:
        try:
            return _helper(x) if x else 0
        except ValueError:
            pass
    return _CACHE.get(f"{_b}")


def _helper(v):
    def inner():
        return v
    return inner


def _dead():
    return 1


class _Hidden:
    def method(self):
        match self:
            case 1:
                return 1
            case 2:
                return 2
            case _:
                return 3
