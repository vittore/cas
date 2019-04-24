package org.apereo.cas.oidc.profile;

import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.authentication.principal.PrincipalFactory;
import org.apereo.cas.authentication.principal.Service;
import org.apereo.cas.configuration.CasConfigurationProperties;
import org.apereo.cas.oidc.OidcConstants;
import org.apereo.cas.oidc.claims.BaseOidcScopeAttributeReleasePolicy;
import org.apereo.cas.oidc.claims.OidcAddressScopeAttributeReleasePolicy;
import org.apereo.cas.oidc.claims.OidcEmailScopeAttributeReleasePolicy;
import org.apereo.cas.oidc.claims.OidcPhoneScopeAttributeReleasePolicy;
import org.apereo.cas.oidc.claims.OidcProfileScopeAttributeReleasePolicy;
import org.apereo.cas.services.ChainingAttributeReleasePolicy;
import org.apereo.cas.services.DenyAllAttributeReleasePolicy;
import org.apereo.cas.services.OidcRegisteredService;
import org.apereo.cas.services.RegisteredService;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.support.oauth.profile.DefaultOAuth20ProfileScopeToAttributesFilter;
import org.apereo.cas.ticket.accesstoken.AccessToken;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.jooq.lambda.Unchecked;
import org.pac4j.core.context.J2EContext;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * This is {@link OidcProfileScopeToAttributesFilter}.
 *
 * @author Misagh Moayyed
 * @since 5.1.0
 */
@Slf4j
public class OidcProfileScopeToAttributesFilter extends DefaultOAuth20ProfileScopeToAttributesFilter {
    private final Map<String, BaseOidcScopeAttributeReleasePolicy> filters;
    private final Collection<BaseOidcScopeAttributeReleasePolicy> userScopes;

    private final PrincipalFactory principalFactory;
    private final ServicesManager servicesManager;
    private final CasConfigurationProperties casProperties;

    public OidcProfileScopeToAttributesFilter(final PrincipalFactory principalFactory,
                                              final ServicesManager servicesManager,
                                              final Collection<BaseOidcScopeAttributeReleasePolicy> userScopes,
                                              final CasConfigurationProperties casProperties) {
        this.casProperties = casProperties;
        this.filters = new LinkedHashMap<>();
        this.principalFactory = principalFactory;
        this.servicesManager = servicesManager;
        this.userScopes = userScopes;

        configureAttributeReleasePoliciesByScope();
    }

    private void configureAttributeReleasePoliciesByScope() {
        val oidc = casProperties.getAuthn().getOidc();
        val packageName = BaseOidcScopeAttributeReleasePolicy.class.getPackage().getName();
        val reflections =
            new Reflections(new ConfigurationBuilder()
                .filterInputsBy(new FilterBuilder().includePackage(packageName))
                .setUrls(ClasspathHelper.forPackage(packageName))
                .setScanners(new SubTypesScanner(true)));

        val subTypes = reflections.getSubTypesOf(BaseOidcScopeAttributeReleasePolicy.class);
        subTypes.forEach(Unchecked.consumer(t -> {
            val ex = t.getDeclaredConstructor().newInstance();
            if (oidc.getScopes().contains(ex.getScopeName())) {
                LOGGER.trace("Found OpenID Connect scope [{}] to filter attributes", ex.getScopeName());
                filters.put(ex.getScopeName(), ex);
            } else {
                LOGGER.debug("OpenID Connect scope [{}] is not configured for use and will be ignored", ex.getScopeName());
            }
        }));

        if (!userScopes.isEmpty()) {
            LOGGER.debug("Configuring attributes release policies for user-defined scopes [{}]", userScopes);
            userScopes.forEach(t -> filters.put(t.getScopeName(), t));
        }
    }

    @Override
    public Principal filter(final Service service, final Principal profile,
                            final RegisteredService registeredService,
                            final J2EContext context, final AccessToken accessToken) {
        val principal = super.filter(service, profile, registeredService, context, accessToken);

        if (registeredService instanceof OidcRegisteredService) {
            val scopes = new LinkedHashSet<String>(accessToken.getScopes());
            if (!scopes.contains(OidcConstants.StandardScopes.OPENID.getScope())) {
                LOGGER.warn("Request does not indicate a scope [{}] that can identify an OpenID Connect request. "
                    + "This is a REQUIRED scope that MUST be present in the request. Given its absence, "
                    + "CAS will not process any attribute claims and will return the authenticated principal as is.", scopes);
                return principal;
            }

            val oidcService = (OidcRegisteredService) registeredService;
            scopes.retainAll(oidcService.getScopes());

            val attributes = filterAttributesByScope(scopes, principal, service, oidcService, accessToken);
            LOGGER.debug("Final collection of attributes filtered by scopes [{}] are [{}]", scopes, attributes);
            return this.principalFactory.createPrincipal(profile.getId(), attributes);
        }
        return principal;
    }

    private Map<String, List<Object>> filterAttributesByScope(final Collection<String> scopes,
                                                              final Principal principal,
                                                              final Service service,
                                                              final RegisteredService registeredService,
                                                              final AccessToken accessToken) {
        if (scopes.isEmpty()) {
            val attributes = principal.getAttributes();
            LOGGER.trace("No defined scopes are available to instruct attribute release policies for [{}]. "
                    + "CAS will authorize the collection of resolved attributes [{}] for release to [{}}",
                registeredService.getServiceId(), attributes, service.getId());
            return attributes;
        }

        val attributes = new LinkedHashMap<String, List<Object>>();
        scopes
            .stream()
            .distinct()
            .filter(this.filters::containsKey)
            .forEach(s -> {
                val policy = filters.get(s);
                attributes.putAll(policy.getAttributes(principal, service, registeredService));
            });
        return attributes;
    }

    @Override
    public void reconcile(final RegisteredService service) {
        if (!(service instanceof OidcRegisteredService)) {
            super.reconcile(service);
            return;
        }

        LOGGER.trace("Reconciling OpenId Connect scopes and claims for [{}]", service.getServiceId());

        val policy = new ChainingAttributeReleasePolicy();
        val oidcService = OidcRegisteredService.class.cast(service);

        val definedServiceScopes = oidcService.getScopes();
        definedServiceScopes.forEach(s -> {
            LOGGER.trace("Reviewing scope [{}] for [{}]", s, service.getServiceId());

            try {
                val scope = OidcConstants.StandardScopes.valueOf(s.trim().toUpperCase());
                switch (scope) {
                    case EMAIL:
                        LOGGER.debug("Mapped [{}] to attribute release policy [{}]", s, OidcEmailScopeAttributeReleasePolicy.class.getSimpleName());
                        policy.getPolicies().add(new OidcEmailScopeAttributeReleasePolicy());
                        break;
                    case ADDRESS:
                        LOGGER.debug("Mapped [{}] to attribute release policy [{}]", s,
                            OidcAddressScopeAttributeReleasePolicy.class.getSimpleName());
                        policy.getPolicies().add(new OidcAddressScopeAttributeReleasePolicy());
                        break;
                    case PROFILE:
                        LOGGER.debug("Mapped [{}] to attribute release policy [{}]", s,
                            OidcProfileScopeAttributeReleasePolicy.class.getSimpleName());
                        policy.getPolicies().add(new OidcProfileScopeAttributeReleasePolicy());
                        break;
                    case PHONE:
                        LOGGER.debug("Mapped [{}] to attribute release policy [{}]", s,
                            OidcProfileScopeAttributeReleasePolicy.class.getSimpleName());
                        policy.getPolicies().add(new OidcPhoneScopeAttributeReleasePolicy());
                        break;
                    case OFFLINE_ACCESS:
                        LOGGER.debug("Given scope [{}], service [{}] is marked to generate refresh tokens", s, service.getId());
                        oidcService.setGenerateRefreshToken(true);
                        break;
                    default:
                        LOGGER.debug("Scope [{}] is unsupported for service [{}]", s, service.getId());
                        break;
                }
            } catch (final Exception e) {
                LOGGER.debug("[{}] appears to be a user-defined scope and does not match any of the predefined standard scopes. "
                    + "Checking [{}] against user-defined scopes provided as [{}]", s, s, userScopes);

                val userPolicy = userScopes
                    .stream()
                    .filter(t -> t.getScopeName().equals(s.trim()))
                    .findFirst()
                    .orElse(null);

                if (userPolicy != null) {
                    LOGGER.debug("Mapped user-defined scope [{}] to attribute release policy [{}]", s, userPolicy);
                    policy.getPolicies().add(userPolicy);
                }
            }
        });

        if (definedServiceScopes.isEmpty()) {
            LOGGER.trace("Registered service [{}] does not define any scopes to control attribute release policies. "
                + "CAS will allow the existing attribute release policies assigned to the service to operate without a scope.", service.getServiceId());
        } else if (policy.getPolicies().isEmpty()) {
            LOGGER.debug("No attribute release policy could be determined based on given scopes. "
                + "No claims/attributes will be released to [{}]", service.getServiceId());
            oidcService.setAttributeReleasePolicy(new DenyAllAttributeReleasePolicy());
        } else {
            oidcService.setAttributeReleasePolicy(policy);
        }

        LOGGER.trace("Scope/claim reconciliation for service [{}] resulted in the following attribute release policy [{}]",
            service.getServiceId(), oidcService.getAttributeReleasePolicy());

        if (!oidcService.equals(service)) {
            LOGGER.trace("Saving scope/claim reconciliation results for service [{}] into registry", service.getServiceId());
            this.servicesManager.save(oidcService);
            LOGGER.debug("Saved service [{}] into registry", service.getServiceId());
        } else {
            LOGGER.trace("No changes detected in service [{}] after scope/claim reconciliation", service.getId());
        }
    }
}