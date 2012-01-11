/**
 * Mule CMIS Connector
 *
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.module.cmis;

import org.apache.chemistry.opencmis.client.api.ChangeEvent;
import org.apache.chemistry.opencmis.client.api.ChangeEvents;
import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Policy;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.Relationship;
import org.apache.chemistry.opencmis.client.api.Repository;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.runtime.ChangeEventsImpl;
import org.apache.chemistry.opencmis.client.runtime.ObjectIdImpl;
import org.apache.chemistry.opencmis.client.runtime.OperationContextImpl;
import org.apache.chemistry.opencmis.client.runtime.SessionFactoryImpl;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.SessionParameter;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.Acl;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.data.RepositoryInfo;
import org.apache.chemistry.opencmis.commons.enums.AclPropagation;
import org.apache.chemistry.opencmis.commons.enums.BindingType;
import org.apache.chemistry.opencmis.commons.enums.UnfileObject;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link CMISFacade} that use Apache Chemistry Project.
 */
public class ChemistryCMISFacade implements CMISFacade {

    private Session session;
    private Map<String, String> connectionParameters;

    public ChemistryCMISFacade(String username,
                               String password,
                               String repositoryId,
                               String baseURL,
                               boolean useAtomPub) {
        this.connectionParameters = paramMap(username, password, repositoryId, baseURL, useAtomPub);
        this.session = createSession(connectionParameters);
    }

    public List<Repository> repositories() {
        return SessionFactoryImpl.newInstance().getRepositories(connectionParameters);
    }

    public RepositoryInfo repositoryInfo() {
        return session.getRepositoryInfo();
    }

    public ChangeEvents changelog(String changeLogToken, boolean includeProperties) {
        boolean hasMore = false;
        String token = changeLogToken;

        List<ChangeEvent> changeEvents = new ArrayList<ChangeEvent>();
        long totalNumItems = 0;
        // follow the pages
        do {
            ChangeEvents events = session.getContentChanges(token, includeProperties, 50);
            totalNumItems += events.getTotalNumItems();

            changeEvents.addAll(events.getChangeEvents());
            if (events.getHasMoreItems()) {
                String t = events.getLatestChangeLogToken();
                if (t != null && !t.equals(token)) {
                    hasMore = true;
                    token = t;
                }
            }
        }
        while (hasMore);

        return new ChangeEventsImpl(token, changeEvents, false, totalNumItems);
    }

    public CmisObject getObjectById(String objectId) {
        try {
            return session.getObject(session.createObjectId(objectId), createOperationContext(null, null));
        } catch (CmisObjectNotFoundException e) {
            return null;
        }
    }

    public CmisObject getObjectByPath(String path) {
        try {
            return session.getObjectByPath(path, createOperationContext(null, null));
        } catch (CmisObjectNotFoundException e) {
            return null;
        } catch (CmisInvalidArgumentException e) {
            return null;
        }
    }

    public ObjectId createDocumentById(String objectId,
                                       String filename,
                                       Object content,
                                       String mimeType,
                                       org.mule.module.cmis.VersioningState versioningState,
                                       String objectType,
                                       Map<String, String> properties) {
        Validate.notEmpty(objectId, "objectId is empty");

        return createDocument(
                session.getObject(session.createObjectId(objectId)),
                filename, content, mimeType, versioningState, objectType, properties);
    }

    public ObjectId createDocumentByPath(String folderPath,
                                         String filename,
                                         Object content,
                                         String mimeType,
                                         org.mule.module.cmis.VersioningState versioningState,
                                         String objectType,
                                         Map<String, String> properties,
                                         boolean force) {
        Validate.notEmpty(folderPath, "folderPath is empty");
        return createDocument(force ? getOrCreateFolderByPath(folderPath) : session.getObjectByPath(folderPath),
                filename, content, mimeType, versioningState, objectType, properties);
    }

    private CmisObject getOrCreateFolderByPath(String folderPath) {
        try {
            return session.getObjectByPath(folderPath);
        } catch (CmisObjectNotFoundException e) {
            return createFolderStructure(folderPath);
        }
    }

    /**
     * For each folder in the given folder path, creates it if necessary.
     * Notice: this implementation checks that the folder exists, and if not creates it.
     * This is not efficient, it would be better to try to just try to create it
     * and catch {@link CmisContentAlreadyExistsException}, but currently that exception
     * is not being thrown - it seems like a server's bug
     */
    private CmisObject createFolderStructure(String folderPath) {
        String[] folderNames = StringUtils.split(folderPath, "/");
        String currentObjectId = getObjectByPath("/").getId();
        String currentPath = "/";
        for (String folder : folderNames) {
            currentPath = currentPath + folder + "/";
            CmisObject currentObject = getObjectByPath(currentPath);
            currentObjectId = currentObject != null
                    ? currentObject.getId()
                    : createFolder(folder, currentObjectId).getId();
        }
        return getObjectById(currentObjectId);
    }

    /**
     * create a document
     */
    protected ObjectId createDocument(
            CmisObject folder,
            String filename,
            Object content,
            String mimeType,
            org.mule.module.cmis.VersioningState versioningState,
            String objectType,
            Map<String, String> extraProperties) {
        Validate.notNull(folder, "folder is null");
        Validate.notEmpty(filename, "filename is empty");
        Validate.notNull(content, "content is null");
        Validate.notEmpty(mimeType, "did you mean application/octet-stream?");
        Validate.notNull(versioningState, "versionState is null");
        VersioningState vs = null;
        try {
            vs = VersioningState.valueOf(versioningState.name());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format(
                    "Illegal value for versioningState. Given `%s' could be: ",
                    versioningState, Arrays.toString(VersioningState.values())), e);
        }

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(PropertyIds.OBJECT_TYPE_ID, objectType);
        properties.put(PropertyIds.NAME, filename);
        if (extraProperties != null) {
            properties.putAll(extraProperties);
        }
        return session.createDocument(properties,
                session.createObjectId(folder.getId()),
                createContentStream(filename, mimeType, content), vs);
    }

    public static ContentStream createContentStream(String filename,
                                                    String mimeType,
                                                    Object content) {
        ContentStreamImpl ret;

        if (content instanceof String) {
            ret = new ContentStreamImpl(filename, mimeType, (String) content);
        } else {
            ret = new ContentStreamImpl();
            ret.setFileName(filename);
            ret.setMimeType(mimeType);
            if (content instanceof InputStream) {
                ret.setStream((InputStream) content);
            } else if (content instanceof byte[]) {
                ret.setStream(new ByteArrayInputStream((byte[]) content));
            } else {
                throw new IllegalArgumentException("Don't know how to handle content of type"
                        + content.getClass());
            }
        }

        return ret;
    }

    public ObjectId createFolder(String folderName, String parentObjectId) {
        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put(PropertyIds.NAME, folderName);
        properties.put(PropertyIds.OBJECT_TYPE_ID, "cmis:folder");
        try {
            return session.createFolder(properties, session.getObject(
                    session.createObjectId(parentObjectId)));
        } catch (CmisContentAlreadyExistsException e) {
            CmisObject object = session.getObject(session.createObjectId(parentObjectId));
            if (!(object instanceof Folder)) {
                throw new IllegalArgumentException(parentObjectId + " is not a folder");
            }
            Folder folder = (Folder) object;
            for (CmisObject o : folder.getChildren()) {
                if (o.getName().equals(folderName)) {
                    return session.createObjectId(o.getId());
                }
            }

            return null;
        }
    }

    public ObjectType getTypeDefinition(String typeId) {
        Validate.notEmpty(typeId, "typeId is empty");
        return session.getTypeDefinition(typeId);
    }

    public ItemIterable<Document> getCheckoutDocs(String filter,
                                                  String orderBy) {
        return session.getCheckedOutDocs(createOperationContext(filter, orderBy));
    }

    public ItemIterable<QueryResult> query(String statement,
                                           Boolean searchAllVersions, String filter,
                                           String orderBy) {
        Validate.notEmpty(statement, "statement is empty");
        Validate.notNull(searchAllVersions, "searchAllVersions is empty");

        OperationContext ctx = createOperationContext(filter, orderBy);
        return session.query(statement, searchAllVersions, ctx);
    }

    public List<Folder> getParentFolders(CmisObject cmisObject, String objectId) {
        validateObjectOrId(cmisObject, objectId);
        validateRedundantIdentifier(cmisObject, objectId);
        CmisObject target = getCmisObject(cmisObject, objectId);

        if (target != null && target instanceof FileableCmisObject) {
            return ((FileableCmisObject) target).getParents();
        }
        return null;
    }

    public Object folder(Folder folder, String folderId,
                         NavigationOptions get, Integer depth,
                         String filter, String orderBy) {
        validateObjectOrId(folder, folderId);
        validateRedundantIdentifier(folder, folderId);

        Folder target = getCmisObject(folder, folderId, Folder.class);
        Object ret = null;

        if (target != null) {
            if (get.equals(NavigationOptions.DESCENDANTS) || get.equals(NavigationOptions.TREE)) {
                Validate.notNull(depth, "depth is null");
            }

            if (get.equals(NavigationOptions.PARENT)) {
                ret = target.getFolderParent();
            } else {
                OperationContext ctx = createOperationContext(filter, orderBy);
                if (get.equals(NavigationOptions.CHILDREN)) {
                    ret = target.getChildren(ctx);
                } else if (get.equals(NavigationOptions.DESCENDANTS)) {
                    ret = target.getDescendants(depth, ctx);
                } else if (get.equals(NavigationOptions.TREE)) {
                    ret = target.getFolderTree(depth, ctx);
                }
            }
        }

        return ret;
    }

    public ContentStream getContentStream(CmisObject cmisObject, String objectId) {
        validateObjectOrId(cmisObject, objectId);
        validateRedundantIdentifier(cmisObject, objectId);

        CmisObject target = getCmisObject(cmisObject, objectId);

        if (target != null && target instanceof Document) {
            return ((Document) target).getContentStream();
        }
        return null;
    }

    public FileableCmisObject moveObject(FileableCmisObject cmisObject,
                                         String objectId,
                                         String sourceFolderId,
                                         String targetFolderId) {
        validateObjectOrId(cmisObject, objectId);
        validateRedundantIdentifier(cmisObject, objectId);
        Validate.notEmpty(sourceFolderId, "sourceFolderId is empty");
        Validate.notEmpty(targetFolderId, "targetFolderId is empty");

        FileableCmisObject target = getCmisObject(cmisObject, objectId, FileableCmisObject.class);
        if (target != null) {
            return target.move(new ObjectIdImpl(sourceFolderId), new ObjectIdImpl(targetFolderId));
        }
        return null;
    }

    public CmisObject updateObjectProperties(CmisObject cmisObject,
                                             String objectId,
                                             Map<String, String> properties) {
        validateObjectOrId(cmisObject, objectId);
        validateRedundantIdentifier(cmisObject, objectId);
        Validate.notNull(properties, "properties is null");

        CmisObject target = getCmisObject(cmisObject, objectId);
        if (target != null) {
            return target.updateProperties(properties);
        }
        return null;
    }

    public void delete(CmisObject cmisObject, String objectId, boolean allVersions) {
        validateObjectOrId(cmisObject, objectId);
        validateRedundantIdentifier(cmisObject, objectId);

        CmisObject target = getCmisObject(cmisObject, objectId);
        if (target != null) {
            target.delete(allVersions);
        }
    }

    public List<String> deleteTree(CmisObject folder, String folderId,
                                   boolean allversions, UnfileObject unfile, boolean continueOnFailure) {
        validateObjectOrId(folder, folderId);
        validateRedundantIdentifier(folder, folderId);
        CmisObject target = getCmisObject(folder, folderId);
        if (target != null && target instanceof Folder) {
            return ((Folder) target).deleteTree(allversions, unfile, continueOnFailure);
        }
        return null;
    }

    public List<Relationship> getObjectRelationships(CmisObject cmisObject,
                                                     String objectId) {
        validateObjectOrId(cmisObject, objectId);
        validateRedundantIdentifier(cmisObject, objectId);
        CmisObject target = getCmisObject(cmisObject, objectId);
        if (target != null) {
            return target.getRelationships();
        }
        return null;
    }

    public Acl getAcl(CmisObject cmisObject, String objectId) {
        validateObjectOrId(cmisObject, objectId);
        validateRedundantIdentifier(cmisObject, objectId);
        CmisObject target = getCmisObject(cmisObject, objectId);
        if (target != null) {
            return target.getAcl();
        }
        return null;
    }

    public List<Document> getAllVersions(CmisObject document, String documentId,
                                         String filter, String orderBy) {
        validateObjectOrId(document, documentId);
        validateRedundantIdentifier(document, documentId);
        CmisObject target = getCmisObject(document, documentId);

        if (target instanceof Document) {
            OperationContext ctx = createOperationContext(filter, orderBy);
            return ((Document) target).getAllVersions(ctx);
        }
        return null;
    }


    public ObjectId checkOut(CmisObject document, String documentId) {
        validateObjectOrId(document, documentId);
        validateRedundantIdentifier(document, documentId);
        CmisObject target = getCmisObject(document, documentId);

        if (target != null && target instanceof Document) {
            return ((Document) target).checkOut();
        }
        return null;
    }


    public void cancelCheckOut(CmisObject document, String documentId) {
        validateObjectOrId(document, documentId);
        validateRedundantIdentifier(document, documentId);
        CmisObject target = getCmisObject(document, documentId);
        if (target != null && target instanceof Document) {
            ((Document) target).cancelCheckOut();
        }
    }

    public ObjectId checkIn(CmisObject document, String documentId,
                            Object content, String filename,
                            String mimeType, boolean major,
                            String checkinComment,
                            Map<String, String> properties) {
        validateObjectOrId(document, documentId);
        validateRedundantIdentifier(document, documentId);
        Validate.notEmpty(filename, "filename is empty");
        Validate.notNull(content, "content is null");
        Validate.notEmpty(mimeType, "did you mean application/octet-stream?");
        Validate.notEmpty(checkinComment, "checkinComment is empty");

        CmisObject target = getCmisObject(document, documentId);
        if (target != null && target instanceof Document) {
            Document doc = (Document) target;
            return doc.checkIn(major, coalesceProperties(properties),
                    createContentStream(filename, mimeType, content),
                    checkinComment);
        }
        return null;
    }

    private Map<String, String> coalesceProperties(Map<String, String> properties) {
        return properties != null ? properties : Collections.<String, String>emptyMap();
    }


    public Acl applyAcl(CmisObject cmisObject, String objectId, List<Ace> addAces,
                        List<Ace> removeAces, AclPropagation aclPropagation) {
        validateObjectOrId(cmisObject, objectId);
        validateRedundantIdentifier(cmisObject, objectId);
        CmisObject target = getCmisObject(cmisObject, objectId);
        if (target != null) {
            return target.applyAcl(addAces, removeAces, aclPropagation);
        }
        return null;
    }

    public List<Policy> getAppliedPolicies(CmisObject cmisObject, String objectId) {
        validateObjectOrId(cmisObject, objectId);
        validateRedundantIdentifier(cmisObject, objectId);
        CmisObject target = getCmisObject(cmisObject, objectId);
        if (target != null) {
            return target.getPolicies();
        }
        return null;
    }

    public void applyPolicy(CmisObject cmisObject, String objectId, List<ObjectId> policyIds) {
        validateObjectOrId(cmisObject, objectId);
        validateRedundantIdentifier(cmisObject, objectId);
        Validate.notNull(policyIds);
        CmisObject target = getCmisObject(cmisObject, objectId);
        if (target != null) {
            target.applyPolicy(policyIds.toArray(new ObjectId[policyIds.size()]));
        }
    }


    /**
     * Validates that either a CmisObject or it's ID has been provided.
     */
    private static void validateObjectOrId(CmisObject object, String objectId) {
        if (object == null && StringUtils.isBlank(objectId)) {
            throw new IllegalArgumentException("Both the cmis object and it's ID are not set");
        }
    }

    /**
     * Validates that and object's ID is the one provided, in case both are not null or blank.
     */
    private static void validateRedundantIdentifier(CmisObject object, String objectId) {
        if (object != null && StringUtils.isNotBlank(objectId) && !object.getId().equals(objectId)) {
            throw new IllegalArgumentException("The id provided does not match the object's ID");
        }
    }

    private CmisObject getCmisObject(CmisObject object, String objectId) {
        return getCmisObject(object, objectId, CmisObject.class);
    }

    /**
     * Returns the object if it is not null. Otherwise, get the object by ID and
     * returns it if types match. Returns null if types don't match.
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    private <T> T getCmisObject(T object, String objectId, Class<T> clazz) {
        if (object != null) {
            return object;
        } else {
            CmisObject obj = getObjectById(objectId);
            if (clazz.isAssignableFrom(obj.getClass())) {
                return (T) obj;
            }
            return null;
        }
    }


    private static OperationContext createOperationContext(String filter,
                                                           String orderBy) {
        OperationContext ctx = new OperationContextImpl();
        ctx.setIncludeAcls(true);
        ctx.setIncludePolicies(true);
        if (StringUtils.isNotBlank(filter) || StringUtils.isNotBlank(orderBy)) {
            if (StringUtils.isNotBlank(filter)) {
                ctx.setFilterString(filter);
            }
            if (StringUtils.isNotBlank(orderBy)) {
                ctx.setOrderBy(orderBy);
            }
        }
        return ctx;
    }

    private static Map<String, String> paramMap(String username,
                                                String password,
                                                String repositoryId,
                                                String baseURL,
                                                boolean useAtomPub) {
        Validate.notEmpty(username, "username is empty");
        Validate.notEmpty(password, "password is empty");
        Validate.notEmpty(repositoryId, "repository-id is empty");
        Validate.notEmpty(baseURL, "base-url is empty");

        Map<String, String> parameters = new HashMap<String, String>();

        // user credentials
        parameters.put(SessionParameter.USER, username);
        parameters.put(SessionParameter.PASSWORD, password);

        // connection settings... we prefer SOAP over ATOMPUB because some rare
        // behaviurs with the ChangeEvents.getLatestChangeLogToken().
        if (!useAtomPub) {
            parameters.put(SessionParameter.BINDING_TYPE, BindingType.WEBSERVICES.value());
            parameters.put(SessionParameter.WEBSERVICES_ACL_SERVICE, baseURL + "ACLService?wsdl");
            parameters.put(SessionParameter.WEBSERVICES_DISCOVERY_SERVICE, baseURL + "DiscoveryService?wsdl");
            parameters.put(SessionParameter.WEBSERVICES_MULTIFILING_SERVICE, baseURL + "MultiFilingService?wsdl");
            parameters.put(SessionParameter.WEBSERVICES_NAVIGATION_SERVICE, baseURL + "NavigationService?wsdl");
            parameters.put(SessionParameter.WEBSERVICES_OBJECT_SERVICE, baseURL + "ObjectService?wsdl");
            parameters.put(SessionParameter.WEBSERVICES_POLICY_SERVICE, baseURL + "PolicyService?wsdl");
            parameters.put(SessionParameter.WEBSERVICES_RELATIONSHIP_SERVICE, baseURL
                    + "RelationshipService?wsdl");
            parameters.put(SessionParameter.WEBSERVICES_REPOSITORY_SERVICE, baseURL + "RepositoryService?wsdl");
            parameters.put(SessionParameter.WEBSERVICES_VERSIONING_SERVICE, baseURL + "VersioningService?wsdl");
        } else {
            parameters.put(SessionParameter.BINDING_TYPE, BindingType.ATOMPUB.value());
            parameters.put(SessionParameter.ATOMPUB_URL, baseURL);
        }

        parameters.put(SessionParameter.REPOSITORY_ID, repositoryId);

        // session locale
        parameters.put(SessionParameter.LOCALE_ISO3166_COUNTRY, "");
        parameters.put(SessionParameter.LOCALE_ISO639_LANGUAGE, "en");

        return parameters;
    }

    private static Session createSession(Map<String, String> parameters) {
        Validate.notNull(parameters);
        return SessionFactoryImpl.newInstance().createSession(parameters);
    }
}