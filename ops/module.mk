# Makefile for the ops module

REQUIRED_PYTHON_ENVIRONMENT := requirements.txt
include $(MAKE_INCLUDE_DIR)/Makefile.module

SRC_DIR  := $(MODULE_DIR)

# Remove any python caches
CLEAN_DIRS += \
	$(shell $(FIND) $(MODULE_DIR) -type d -name '__pycache__')
