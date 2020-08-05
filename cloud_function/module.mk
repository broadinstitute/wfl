# Makefile for the cloud_function module

include $(MAKE_INCLUDE_DIR)/common.mk
include $(MAKE_INCLUDE_DIR)/Makefile.module

SRC_DIR	 := $(MODULE_DIR)/src
TEST_DIR := $(MODULE_DIR)/tests

SCM_SRC := \
	$(MODULE_DIR)/__init__.py \
	$(MODULE_DIR)/main.py

SCM_TEST = $(shell $(FIND) $(TEST_DIR) -name '*.py')

VIRTUAL_ENVIRONMENT := $(DERIVED_MODULE_DIR)/venv

$(PREBUILD): $(MODULE_DIR)/dev-requirements.txt
	$(PYTHON) -m venv $(VIRTUAL_ENVIRONMENT)
	(                                                  \
		$(SOURCE) $(VIRTUAL_ENVIRONMENT)/bin/activate; \
		$(PYTHON) -m pip install -r $<;                \
	)
	@$(TOUCH) $@

WFL_URL := https://workflow-launcher.gotc-dev.broadinstitute.org

$(CHECK): $(SCM_SRC) $(SCM_TEST)
	(                                                  \
		$(SOURCE) $(VIRTUAL_ENVIRONMENT)/bin/activate; \
		$(EXPORT) WFL_URL=$(WFL_URL);                  \
		$(PYTHON) -m pytest $(TEST_DIR)/unit_tests.py; \
	)
	@$(TOUCH) $@
