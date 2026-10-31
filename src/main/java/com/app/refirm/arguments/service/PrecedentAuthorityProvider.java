package com.app.refirm.arguments.service;

import com.app.refirm.arguments.domain.VerifiedPrecedentAuthority;
import com.app.refirm.workspace.entity.LegalWorkspace;

import java.util.List;

/**
 * Strategy interface for resolving verified precedent authorities for a workspace.
 * Follows the Open-Closed Principle so Step 5 (Precedent Finder Engine) can plug in seamlessly.
 */
public interface PrecedentAuthorityProvider {

    List<VerifiedPrecedentAuthority> resolveVerifiedPrecedents(LegalWorkspace workspace);
}
