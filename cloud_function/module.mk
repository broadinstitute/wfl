# Makefile for the cloud_function module

REQUIRED_PYTHON_ENVIRONMENT := dev-requirements.txt
include $(MAKE_INCLUDE_DIR)/Makefile.module

SRC_DIR	 := $(MODULE_DIR)
TEST_DIR := $(MODULE_DIR)/tests

SCM_SRC := \
	$(SRC_DIR)/__init__.py \
	$(SRC_DIR)/main.py

TEST_SCM_SRC = $(shell $(FIND) $(TEST_DIR) -type f -name '*.py')

WFL_URL := https://workflow-launcher.gotc-dev.broadinstitute.org
LOGFILE := $(DERIVED_MODULE_DIR)/test.log
$(CHECK): $(SCM_SRC) $(TEST_SCM_SRC)
	$(call using-python-environment,                                      \
		$(EXPORT) WFL_URL=$(WFL_URL);                                     \
		$(PYTHON) -m pytest $(TEST_DIR)/unit_tests.py | $(TEE) $(LOGFILE) \
	)
	@$(TOUCH) $@

# Remove any python caches
CLEAN_DIRS += \
	$(shell $(FIND) $(MODULE_DIR) -type d -name '__pycache__') \
	$(shell $(FIND) $(MODULE_DIR) -type d -name '.pytest_cache')
