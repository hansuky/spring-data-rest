/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.rest.webmvc;

import static org.springframework.data.rest.webmvc.ControllerUtils.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.support.RepositoryInvoker;
import org.springframework.data.rest.core.mapping.ResourceMappings;
import org.springframework.data.rest.core.mapping.SearchResourceMappings;
import org.springframework.data.rest.webmvc.support.DefaultedPageable;
import org.springframework.data.rest.webmvc.support.RepositoryEntityLinks;
import org.springframework.data.web.HateoasSortHandlerMethodArgumentResolver;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityLinks;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.Links;
import org.springframework.hateoas.Resource;
import org.springframework.hateoas.ResourceSupport;
import org.springframework.hateoas.Resources;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.WebRequest;

/**
 * Controller to lookup and execute searches on a given repository.
 * 
 * @author Jon Brisbin
 * @author Oliver Gierke
 */
@RepositoryRestController
class RepositorySearchController extends AbstractRepositoryRestController {

	private static final String SEARCH = "/search";
	private static final String BASE_MAPPING = "/{repository}" + SEARCH;

	private final RepositoryEntityLinks entityLinks;
	private final ResourceMappings mappings;
	private final PagedResourcesAssembler<Object> assembler;
	private final HateoasSortHandlerMethodArgumentResolver sortResolver;

	/**
	 * Creates a new {@link RepositorySearchController} using the given {@link PagedResourcesAssembler},
	 * {@link EntityLinks} and {@link ResourceMappings}.
	 * 
	 * @param assembler must not be {@literal null}.
	 * @param entityLinks must not be {@literal null}.
	 * @param mappings must not be {@literal null}.
	 */
	@Autowired
	public RepositorySearchController(PagedResourcesAssembler<Object> assembler, RepositoryEntityLinks entityLinks,
			ResourceMappings mappings, HateoasSortHandlerMethodArgumentResolver sortResolver) {

		super(assembler);

		Assert.notNull(entityLinks, "EntityLinks must not be null!");
		Assert.notNull(mappings, "ResourceMappings must not be null!");
		Assert.notNull(sortResolver, "HateoasSortHandlerMethodArgumentResolver must not be null!");

		this.entityLinks = entityLinks;
		this.mappings = mappings;
		this.assembler = assembler;
		this.sortResolver = sortResolver;
	}

	/**
	 * <code>OPTIONS /{repository}/search</code>.
	 * 
	 * @param resourceInformation
	 * @return
	 * @since 2.2
	 */
	@RequestMapping(value = BASE_MAPPING, method = RequestMethod.OPTIONS)
	public HttpEntity<?> optionsForSearches(RootResourceInformation resourceInformation) {

		verifySearchesExposed(resourceInformation);

		HttpHeaders headers = new HttpHeaders();
		headers.setAllow(Collections.singleton(HttpMethod.GET));

		return new ResponseEntity<Object>(headers, HttpStatus.OK);
	}

	/**
	 * <code>HEAD /{repository}/search</code> - Checks whether the search resource is present.
	 * 
	 * @param resourceInformation
	 * @return
	 */
	@RequestMapping(value = BASE_MAPPING, method = RequestMethod.HEAD)
	public HttpEntity<?> headForSearches(RootResourceInformation resourceInformation) {

		verifySearchesExposed(resourceInformation);

		return new ResponseEntity<Object>(HttpStatus.NO_CONTENT);
	}

	/**
	 * <code>GET /{repository}/search</code> - Exposes links to the individual search resources exposed by the backing
	 * repository.
	 * 
	 * @param resourceInformation
	 * @return
	 */
	@ResponseBody
	@RequestMapping(value = BASE_MAPPING, method = RequestMethod.GET)
	public ResourceSupport listSearches(RootResourceInformation resourceInformation) {

		verifySearchesExposed(resourceInformation);

		Links queryMethodLinks = entityLinks.linksToSearchResources(resourceInformation.getDomainType());

		if (queryMethodLinks.isEmpty()) {
			throw new ResourceNotFoundException();
		}

		ResourceSupport result = new ResourceSupport();
		result.add(queryMethodLinks);

		return result;
	}

	/**
	 * Executes the search with the given name.
	 * 
	 * @param request
	 * @param repository
	 * @param search
	 * @param pageable
	 * @return
	 * @throws ResourceNotFoundException
	 */
	@ResponseBody
	@RequestMapping(value = BASE_MAPPING + "/{search}", method = RequestMethod.GET)
	public ResponseEntity<Object> executeSearch(RootResourceInformation resourceInformation, WebRequest request,
			@PathVariable String search, DefaultedPageable pageable, Sort sort, PersistentEntityResourceAssembler assembler) {

		Method method = checkExecutability(resourceInformation, search);
		Object resources = executeQueryMethod(resourceInformation.getInvoker(), request, method, pageable, sort, assembler);

		return new ResponseEntity<Object>(resources, HttpStatus.OK);
	}

	/**
	 * Executes a query method and exposes the results in compact form.
	 * 
	 * @param resourceInformation
	 * @param repository
	 * @param method
	 * @param pageable
	 * @return
	 */
	@ResponseBody
	@RequestMapping(value = BASE_MAPPING + "/{search}", method = RequestMethod.GET, //
			produces = { "application/x-spring-data-compact+json" })
	public ResourceSupport executeSearchCompact(RootResourceInformation resourceInformation, WebRequest request,
			@PathVariable String repository, @PathVariable String search, DefaultedPageable pageable, Sort sort,
			PersistentEntityResourceAssembler assembler) {

		Method method = checkExecutability(resourceInformation, search);
		Object resource = executeQueryMethod(resourceInformation.getInvoker(), request, method, pageable, sort, assembler);

		List<Link> links = new ArrayList<Link>();

		if (resource instanceof Resources && ((Resources<?>) resource).getContent() != null) {

			for (Object obj : ((Resources<?>) resource).getContent()) {
				if (null != obj && obj instanceof Resource) {
					Resource<?> res = (Resource<?>) obj;
					links.add(resourceLink(resourceInformation, res));
				}
			}

		} else if (resource instanceof Resource) {

			Resource<?> res = (Resource<?>) resource;
			links.add(resourceLink(resourceInformation, res));
		}

		return new Resources<Resource<?>>(EMPTY_RESOURCE_LIST, links);
	}

	/**
	 * <code>OPTIONS /{repository}/search/{search}</code>.
	 * 
	 * @param information
	 * @param search
	 * @return
	 * @since 2.2
	 */
	@RequestMapping(value = BASE_MAPPING + "/{search}", method = RequestMethod.OPTIONS)
	public ResponseEntity<Object> optionsForSearch(RootResourceInformation information, @PathVariable String search) {

		checkExecutability(information, search);

		HttpHeaders headers = new HttpHeaders();
		headers.setAllow(Collections.singleton(HttpMethod.GET));

		return new ResponseEntity<Object>(headers, HttpStatus.OK);
	}

	/**
	 * Handles a {@code HEAD} request for individual searches.
	 * 
	 * @param information
	 * @param search
	 * @return
	 * @since 2.2
	 */
	@RequestMapping(value = BASE_MAPPING + "/{search}", method = RequestMethod.HEAD)
	public ResponseEntity<Object> headForSearch(RootResourceInformation information, @PathVariable String search) {

		checkExecutability(information, search);
		return new ResponseEntity<Object>(HttpStatus.NO_CONTENT);
	}

	/**
	 * Checks that the given request is actually executable. Will reject execution if we don't find a search with the
	 * given name.
	 * 
	 * @param resourceInformation
	 * @param searchName
	 * @return
	 */
	private Method checkExecutability(RootResourceInformation resourceInformation, String searchName) {

		SearchResourceMappings searchMapping = verifySearchesExposed(resourceInformation);

		Method method = searchMapping.getMappedMethod(searchName);

		if (method == null) {
			throw new ResourceNotFoundException();
		}

		return method;
	}

	/**
	 * @param invoker
	 * @param request
	 * @param method
	 * @param pageable
	 * @return
	 */
	private Object executeQueryMethod(final RepositoryInvoker invoker, WebRequest request, Method method,
			DefaultedPageable pageable, Sort sort, PersistentEntityResourceAssembler assembler) {

		Map<String, String[]> parameters = request.getParameterMap();
		Object result = invoker.invokeQueryMethod(method, parameters, pageable.getPageable(), sort);

		if (ClassUtils.isPrimitiveOrWrapper(method.getReturnType())) {
			return result;
		}

		return resultToResources(result, assembler, null);
	}

	/**
	 * Verifies that the given {@link RootResourceInformation} has searches exposed.
	 * 
	 * @param resourceInformation
	 */
	private SearchResourceMappings verifySearchesExposed(RootResourceInformation resourceInformation) {

		SearchResourceMappings resourceMappings = resourceInformation.getSearchMappings();

		if (!resourceMappings.isExported()) {
			throw new ResourceNotFoundException();
		}

		return resourceMappings;
	}
}
