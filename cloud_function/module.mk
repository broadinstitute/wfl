# Makefile for the cloud_function module

include $(MAKE_INCLUDE_DIR)/Makefile.module

SRC_DIR	 := $(MODULE_DIR)
TEST_DIR := $(MODULE_DIR)/tests

SCM_SRC := \
	$(SRC_DIR)/__init__.py \
	$(SRC_DIR)/main.py

TEST_SCM_SRC = $(shell $(FIND) $(TEST_DIR) -type f -name '*.py')

VIRTUAL_ENVIRONMENT := $(DERIVED_MODULE_DIR)/.venv

$(PREBUILD): $(MODULE_DIR)/dev-requirements.txt
	$(call make-python-environment, $(VIRTUAL_ENVIRONMENT), $<)
	@$(TOUCH) $@

WFL_URL := https://workflow-launcher.gotc-dev.broadinstitute.org
LOGFILE := $(DERIVED_MODULE_DIR)/test.log
$(CHECK): $(SCM_SRC) $(TEST_SCM_SRC)
	(                                                                      \
		$(SOURCE) $(VIRTUAL_ENVIRONMENT)/bin/activate;                     \
		$(EXPORT) WFL_URL=$(WFL_URL);                                      \
		$(PYTHON) -m pytest $(TEST_DIR)/unit_tests.py | $(TEE) $(LOGFILE); \
	)
	@$(TOUCH) $@
