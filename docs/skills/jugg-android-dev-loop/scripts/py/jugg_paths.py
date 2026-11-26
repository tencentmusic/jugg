"""Global Jugg storage discovery shared by the CLI and agent hooks."""
from pathlib import Path


def global_root(home=None):
    home = Path.home() if home is None else Path(home)
    try:
        text = (home / ".juggdir").read_text(encoding="utf-8").strip()
        key, separator, value = text.partition(":")
        if key.strip() != "juggdir" or not separator or "\n" in text or "\r" in text or "\x00" in text:
            raise ValueError("Invalid Jugg directory locator")
        path = Path(value.strip())
        if not value.strip() or not path.is_absolute():
            raise ValueError("Jugg directory must be absolute")
        return path
    except (OSError, UnicodeError, ValueError):
        return home / ".jugg"
