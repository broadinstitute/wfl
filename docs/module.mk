# Makefile for the cloud_function module

include $(MAKE_INCLUDE_DIR)/Makefile.module

MD_DIR := $(MODULE_DIR)/md
SCM_SRC  = $(shell $(FIND) $(MD_DIR) -type f)

VIRTUAL_ENVIRONMENT := $(DERIVED_MODULE_DIR)/.venv
$(PREBUILD): $(MODULE_DIR)/requirements.txt
	$(call make-python-environment, $(VIRTUAL_ENVIRONMENT), $<)
	$(TOUCH) $@

$(BUILD): $(MODULE_DIR)/mkdocs.yml $(SCM_SRC)
	$(RM) $(DERIVED_MODULE_DIR)/md
	$(CP) -f $< $(DERIVED_MODULE_DIR)
	$(LN) $(MD_DIR) $(DERIVED_MODULE_DIR)
	(                                                    \
		$(SOURCE) $(VIRTUAL_ENVIRONMENT)/bin/activate;   \
		$(CD) $(DERIVED_MODULE_DIR);                     \
		$(PYTHON) -m mkdocs build;                       \
	)
	@$(TOUCH) $@
