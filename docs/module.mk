# Makefile for the cloud_function module

REQUIRED_PYTHON_ENVIRONMENT := requirements.txt
include $(MAKE_INCLUDE_DIR)/Makefile.module

MD_DIR  := $(MODULE_DIR)/md
SCM_SRC  = $(shell $(FIND) $(MD_DIR) -type f)

$(BUILD): $(MODULE_DIR)/mkdocs.yml $(SCM_SRC)
	$(RM) $(DERIVED_MODULE_DIR)/md
	$(CP) -f $< $(DERIVED_MODULE_DIR)
	$(LN) $(MD_DIR) $(DERIVED_MODULE_DIR)
	$(call using-python-environment, \
		$(CD) $(DERIVED_MODULE_DIR); \
		$(PYTHON) -m mkdocs build    \
	)
	@$(TOUCH) $@
