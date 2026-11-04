"""Compatibility entry point for the Backend primary Jev connection check."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'Backend' / 'scripts'))
from check_jev_screen import main

if __name__ == '__main__':
    raise SystemExit(main())
