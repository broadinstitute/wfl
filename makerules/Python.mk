# Makerules for Python Virtual Enviroments
# Conditionally included by defining the variable REQUIRED_PYTHON_ENVIRONMENT
# Virtual evironments are created in derived/venv/$(MODULE) and are cleaned on
# `distclean`
# Example:
# REQUIRED_2P_REPOSREQUIRED_PYTHON_ENVIRONMENTITORIES := requirements.txt
# include $(MAKE_INCLUDE_DIR)/Makefile.module
#
# # Then use
# $(call using-python-enviroment, echo `which python`)

VIRTUAL_ENVIRONMENT_DIR    := $(DERIVED_DIR)/.venv
PYTHON_VIRTUAL_ENVIRONMENT := \
	$(addsuffix /$(MODULE),$(VIRTUAL_ENVIRONMENT_DIR))

# Usage: $(call using-python-enviroment, command[s])
define using-python-environment
	$(SOURCE) $(PYTHON_VIRTUAL_ENVIRONMENT)/bin/activate && ( $1 )
endef

PYTHON_ENVIRONMENT_TIMESTAMP := $(PYTHON_VIRTUAL_ENVIRONMENT).$(TIMESTAMP)
$(PREBUILD): $(PYTHON_ENVIRONMENT_TIMESTAMP)
$(PYTHON_ENVIRONMENT_TIMESTAMP): | $(VIRTUAL_ENVIRONMENT_DIR)
$(PYTHON_ENVIRONMENT_TIMESTAMP): $(REQUIRED_PYTHON_ENVIRONMENT)
	$(PYTHON) -m venv $(PYTHON_VIRTUAL_ENVIRONMENT); \
	$(call using-python-environment,                 \
		$(PYTHON) -m pip install --upgrade pip;      \
		$(PYTHON) -m pip install -r $<)
	@$(TOUCH) $@

$(VIRTUAL_ENVIRONMENT_DIR):
	@$(MKDIR) $@

DISTCLEAN_FILES += $(PYTHON_ENVIRONMENT_TIMESTAMP)
DISTCLEAN_DIRS  += $(PYTHON_VIRTUAL_ENVIRONMENT)
