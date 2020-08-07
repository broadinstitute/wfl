# Makerules for 2p (Broad Institute) Libraries on GitHub
# Conditionally included by defining the list REQUIRED_2P_REPOSITORIES
# 2p repositories are cloned into derived/2p and are cleaed on `distclean`
# Example:
# REQUIRED_2P_REPOSITORIES := wfl
# include $(MAKE_INLUDE_DIR)/Makefile.module

# 2p repository time stamps
SECOND_PARTY_DIR          := $(DERIVED_DIR)/2p
SECOND_PARTY_REPOSITORIES := \
  $(patsubst %, $(SECOND_PARTY_DIR)/%.$(TS),$(REQUIRED_2P_REPOSITORIES))

DISTCLEAN_DIRS  += $(subst .$(TS),,$(SECOND_PARTY_REPOSITORIES))
DISTCLEAN_FILES += $(SECOND_PARTY_REPOSITORIES)

define make-github-url
$(strip \
  $(if $(GITHUB_ACTOR),\
    git@$*.github.com:broadinstitute/$*.git,\
    git@github.com:broadinstitute/$*.git))
endef

$(PREBUILD): $(SECOND_PARTY_REPOSITORIES)
$(SECOND_PARTY_REPOSITORIES): | $(SECOND_PARTY_DIR)
$(SECOND_PARTY_REPOSITORIES): $(SECOND_PARTY_DIR)/%.$(TS):
	$(GIT) clone $(make-github-url) $(SECOND_PARTY_DIR)/$*
	@$(TOUCH) $@

$(SECOND_PARTY_DIR):
	@$(MKDIR) $@
