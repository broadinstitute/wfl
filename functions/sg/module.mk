# Makefile for the functions/sg module

REQUIRED_PYTHON_ENVIRONMENT := dev-requirements.txt
include $(MAKE_INCLUDE_DIR)/modules.mk

SRC_DIR  := $(MODULE_DIR)

SCM_SRC := \
	$(SRC_DIR)/__init__.py \
	$(SRC_DIR)/main.py

LOGFILE := $(DERIVED_MODULE_DIR)/unittest.log

$(LINT): $(SCM_SRC)
	$(call using-python-environment, $(PYTHON) -m flake8 $(SCM_SRC))
	@$(TOUCH) $@

# Remove any python caches
CLEAN_DIRS += \
	$(shell $(FIND) $(MODULE_DIR) -type d -name '__pycache__') \
	$(shell $(FIND) $(MODULE_DIR) -type d -name '.pytest_cache')
