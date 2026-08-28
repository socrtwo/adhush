.PHONY: lint type test all
lint:
	ruff check src tests
type:
	mypy src
test:
	pytest -q
all: lint type test
