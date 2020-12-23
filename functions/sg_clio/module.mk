# Makefile for the functions/sg_clio module

REQUIRED_PYTHON_ENVIRONMENT := dev-requirements.txt
include $(MAKE_INCLUDE_DIR)/modules.mk

SRC_DIR  := $(MODULE_DIR)
TEST_DIR := $(MODULE_DIR)/tests

SCM_SRC := \
	$(SRC_DIR)/__init__.py \
	$(SRC_DIR)/main.py

TEST_SCM_SRC = \
	$(shell $(FIND) $(TEST_DIR) -type f -name '*.py') \
	$(MODULE_DIR)/pytest.ini

LOGFILE := $(DERIVED_MODULE_DIR)/unittest.log
$(UNIT): $(SCM_SRC) $(TEST_SCM_SRC)
	$(call using-python-environment, \
		$(PYTHON) -m pytest $(TEST_DIR)/unit_tests.py | $(TEE) $(LOGFILE))
	@$(TOUCH) $@

$(LINT): $(SCM_SRC)
	$(call using-python-environment, $(PYTHON) -m flake8 $(SCM_SRC))
	@$(TOUCH) $@

# Remove any python caches
CLEAN_DIRS += \
	$(shell $(FIND) $(MODULE_DIR) -type d -name '__pycache__') \
	$(shell $(FIND) $(MODULE_DIR) -type d -name '.pytest_cache')
