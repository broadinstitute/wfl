# Makerules for Python Virtual Enviroments
# Conditionally included by defining the variable REQUIRED_PYTHON_ENVIRONMENT
# Virtual evironments are created in derived/venv/$(MODULE) and are cleaned on
# `distclean`
# Example:
# REQUIRED_2P_REPOSITORIES := requirements.txt
# include $(MAKE_INLUDE_DIR)/Makefile.module
#
# # Then use
# $(call using-python-enviroment, echo `which python`)

VIRTUAL_ENVIRONMENT_DIR := $(DERIVED_DIR)/.venv
PYTHON_VIRTUAL_ENVIRONMENT := \
	$(addsuffix /$(MODULE),$(VIRTUAL_ENVIRONMENT_DIR))

DISTCLEAN_FILES += $(PYTHON_VIRTUAL_ENVIRONMENT).$(TS)
DISTCLEAN_DIRS  += $(PYTHON_VIRTUAL_ENVIRONMENT)

$(PREBUILD): $(PYTHON_VIRTUAL_ENVIRONMENT).$(TS)
$(PYTHON_VIRTUAL_ENVIRONMENT).$(TS): | $(VIRTUAL_ENVIRONMENT_DIR)
$(PYTHON_VIRTUAL_ENVIRONMENT).$(TS): $(REQUIRED_PYTHON_ENVIRONMENT)
	$(PYTHON) -m venv $(PYTHON_VIRTUAL_ENVIRONMENT);      \
	$(SOURCE) $(PYTHON_VIRTUAL_ENVIRONMENT)/bin/activate; \
	$(PYTHON) -m pip install --upgrade pip;               \
	$(PYTHON) -m pip install -r $<;
	@$(TOUCH) $@

$(VIRTUAL_ENVIRONMENT_DIR):
	@$(MKDIR) $@

# Usage: $(call using-python-enviroment, command[s])
define using-python-environment
	$(SOURCE) $(PYTHON_VIRTUAL_ENVIRONMENT)/bin/activate && ( $1 )
endef
