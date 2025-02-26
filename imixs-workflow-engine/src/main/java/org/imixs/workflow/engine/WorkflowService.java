/*  
 *  Imixs-Workflow 
 *  
 *  Copyright (C) 2001-2020 Imixs Software Solutions GmbH,  
 *  http://www.imixs.com
 *  
 *  This program is free software; you can redistribute it and/or 
 *  modify it under the terms of the GNU General Public License 
 *  as published by the Free Software Foundation; either version 2 
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful, 
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of 
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
 *  General Public License for more details.
 *  
 *  You can receive a copy of the GNU General Public
 *  License at http://www.gnu.org/licenses/gpl.html
 *  
 *  Project: 
 *      https://www.imixs.org
 *      https://github.com/imixs/imixs-workflow
 *  
 *  Contributors:  
 *      Imixs Software Solutions GmbH - Project Management
 *      Ralph Soika - Software Developer
 */

package org.imixs.workflow.engine;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.Resource;
import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.imixs.workflow.Adapter;
import org.imixs.workflow.ItemCollection;
import org.imixs.workflow.Model;
import org.imixs.workflow.ModelManager;
import org.imixs.workflow.Plugin;
import org.imixs.workflow.WorkflowContext;
import org.imixs.workflow.WorkflowKernel;
import org.imixs.workflow.WorkflowManager;
import org.imixs.workflow.engine.plugins.ResultPlugin;
import org.imixs.workflow.exceptions.AccessDeniedException;
import org.imixs.workflow.exceptions.InvalidAccessException;
import org.imixs.workflow.exceptions.ModelException;
import org.imixs.workflow.exceptions.PluginException;
import org.imixs.workflow.exceptions.ProcessingErrorException;
import org.imixs.workflow.exceptions.QueryException;

import jakarta.ejb.LocalBean;
import jakarta.ejb.SessionContext;
import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;

/**
 * The WorkflowService is the Java EE Implementation for the Imixs Workflow Core
 * API. This interface acts as a service facade and supports basic methods to
 * create, process and access workitems. The Interface extends the core api
 * interface org.imixs.workflow.WorkflowManager with getter methods to fetch
 * collections of workitems.
 * 
 * @author rsoika
 * 
 */

@DeclareRoles({ "org.imixs.ACCESSLEVEL.NOACCESS", "org.imixs.ACCESSLEVEL.READERACCESS",
        "org.imixs.ACCESSLEVEL.AUTHORACCESS", "org.imixs.ACCESSLEVEL.EDITORACCESS",
        "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@RolesAllowed({ "org.imixs.ACCESSLEVEL.NOACCESS", "org.imixs.ACCESSLEVEL.READERACCESS",
        "org.imixs.ACCESSLEVEL.AUTHORACCESS", "org.imixs.ACCESSLEVEL.EDITORACCESS",
        "org.imixs.ACCESSLEVEL.MANAGERACCESS" })
@Stateless
@LocalBean
public class WorkflowService implements WorkflowManager, WorkflowContext {

    // workitem properties
    public static final String UNIQUEIDREF = "$uniqueidref";
    public static final String READACCESS = "$readaccess";
    public static final String WRITEACCESS = "$writeaccess";
    public static final String PARTICIPANTS = "$participants";
    public static final String DEFAULT_TYPE = "workitem";

    // view properties
    public static final int SORT_ORDER_CREATED_DESC = 0;
    public static final int SORT_ORDER_CREATED_ASC = 1;
    public static final int SORT_ORDER_MODIFIED_DESC = 2;
    public static final int SORT_ORDER_MODIFIED_ASC = 3;

    public static final String INVALID_ITEMVALUE_FORMAT = "INVALID_ITEMVALUE_FORMAT";
    public static final String INVALID_TAG_FORMAT = "INVALID_TAG_FORMAT";

    @Inject
    @Any
    private Instance<Plugin> plugins;

    @Inject
    @Any
    protected Instance<Adapter> adapters;

    @Inject
    DocumentService documentService;

    @Inject
    ModelService modelService;

    @Inject
    ReportService reportService;

    @Resource
    SessionContext ctx;
   
    @Inject
    protected Event<ProcessingEvent> processingEvents;

    @Inject
    protected Event<TextEvent> textEvents;

    private static Logger logger = Logger.getLogger(WorkflowService.class.getName());

    
    
    
    public WorkflowService() {
        super();
    }

    /**
     * This method loads a Workitem with the corresponding uniqueid.
     * 
     */
    public ItemCollection getWorkItem(String uniqueid) {
        return documentService.load(uniqueid);
    }

    /**
     * Returns a collection of workitems containing a '$owner' item belonging to a
     * specified username. The '$owner' item can be controlled by the plug-in
     * {@code org.imixs.workflow.plugins.OwnerPlugin}
     * 
     * @param name        = username for itme '$owner' - if null current username
     *                    will be used
     * @param pageSize    = optional page count (default 20)
     * @param pageIndex   = optional start position
     * @param type        = defines the type property of the workitems to be
     *                    returnd. can be null
     * @param sortBy      -optional field to sort the result
     * @param sortReverse - optional sort direction
     * 
     * @return List of workitems
     * 
     */
    public List<ItemCollection> getWorkListByOwner(String name, String type, int pageSize, int pageIndex, String sortBy,
            boolean sortReverse) {

        if (name == null || "".equals(name))
            name = ctx.getCallerPrincipal().getName();

        String searchTerm = "(";
        if (type != null && !"".equals(type)) {
            searchTerm += " type:\"" + type + "\" AND ";
        }

        // support deprecated namowner field
        searchTerm += " (namowner:\"" + name + "\" OR $owner:\"" + name + "\") )";
        try {
            return documentService.find(searchTerm, pageSize, pageIndex, sortBy, sortReverse);
        } catch (QueryException e) {
            logger.severe("getWorkListByOwner - invalid param: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns a collection of workitems for which the specified user has explicit write permission.
     * The name is a username or role contained in the $WriteAccess attribute of the
     * workItem.
     * 
     * The method returns only workitems the call has sufficient read access for.
     * 
     * @param name        = username or role contained in $writeAccess - if null
     *                    current username will be used
     * @param pageSize    = optional page count (default 20)
     * @param pageIndex   = optional start position
     * @param type        = defines the type property of the workitems to be returned. can be null
     * @param sortBy      -optional field to sort the result
     * @param sortReverse - optional sort direction
     * 
     * @return List of workitems
     * 
     */
    public List<ItemCollection> getWorkListByAuthor(String name, String type, int pageSize, int pageIndex,
            String sortBy, boolean sortReverse) {

        if (name == null || "".equals(name))
            name = ctx.getCallerPrincipal().getName();

        String searchTerm = "(";
        if (type != null && !"".equals(type)) {
            searchTerm += " type:\"" + type + "\" AND ";
        }
        searchTerm += " $writeaccess:\"" + name + "\" )";

        try {
            return documentService.find(searchTerm, pageSize, pageIndex, sortBy, sortReverse);
        } catch (QueryException e) {
            logger.severe("getWorkListByAuthor - invalid param: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns a collection of workitems created by a specified user ($Creator). The
     * behaivor is simmilar to the method getWorkList.
     * 
     * 
     * @param name        = username for property $Creator - if null current
     *                    username will be used
     * @param pageSize    = optional page count (default 20)
     * @param pageIndex   = optional start position
     * @param type        = defines the type property of the workitems to be
     *                    returnd. can be null
     * @param sortBy      -optional field to sort the result
     * @param sortReverse - optional sort direction
     * 
     * @return List of workitems
     * 
     */
    public List<ItemCollection> getWorkListByCreator(String name, String type, int pageSize, int pageIndex,
            String sortBy, boolean sortReverse) {

        if (name == null || "".equals(name))
            name = ctx.getCallerPrincipal().getName();

        String searchTerm = "(";
        if (type != null && !"".equals(type)) {
            searchTerm += " type:\"" + type + "\" AND ";
        }
        searchTerm += " $creator:\"" + name + "\" )";
        try {
            return documentService.find(searchTerm, pageSize, pageIndex, sortBy, sortReverse);
        } catch (QueryException e) {
            logger.severe("getWorkListByCreator - invalid param: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns a collection of workitems where the current user has a writeAccess.
     * This means that at least one of the userNames is contained in
     * the $writeaccess property.
     * 
     * 
     * @param pageSize    = optional page count (default 20)
     * @param pageIndex   = optional start position
     * @param type        = defines the type property of the workitems to be
     *                    returnd. can be null
     * @param sortorder   = defines sortorder (SORT_ORDER_CREATED_DESC = 0
     *                    SORT_ORDER_CREATED_ASC = 1 SORT_ORDER_MODIFIED_DESC = 2
     *                    SORT_ORDER_MODIFIED_ASC = 3)
     * @param sortBy      -optional field to sort the result
     * @param sortReverse - optional sort direction
     * 
     * @return List of workitems
     * 
     */
    public List<ItemCollection> getWorkListByWriteAccess(String type, int pageSize, int pageIndex, String sortBy,
            boolean sortReverse) {
        StringBuffer nameListBuffer = new StringBuffer();
        nameListBuffer.append("(");
        // construct a name list query for $writeaccess
        List<String> userNames = documentService.getUserNameList();
        for (int i=0; i<userNames.size(); i++) {
            String userName= userNames.get(i);
            if (i>0) {
                nameListBuffer.append(" OR ");
            }
            nameListBuffer.append(" $writeaccess:\"" + userName + "\" ");            
        }
        nameListBuffer.append(")");

        String searchTerm = nameListBuffer.toString();
        if (type != null && !"".equals(type)) {
            searchTerm += " AND type:\"" + type + "\"";
        }
        try {
            return documentService.find(searchTerm, pageSize, pageIndex, sortBy, sortReverse);
        } catch (QueryException e) {
            logger.severe("getWorkListByWriteAccess - invalid param: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns a list of workitems filtered by the field $workflowgroup
     * 
     * the method supports still also the deprecated field "txtworkflowgroup"
     * 
     * @param name
     * @param type
     * @param pageSize    = optional page count (default 20)
     * @param pageIndex   = optional start position
     * @param sortBy      -optional field to sort the result
     * @param sortReverse - optional sort direction
     * 
     * @return
     */

    public List<ItemCollection> getWorkListByGroup(String name, String type, int pageSize, int pageIndex, String sortBy,
            boolean sortReverse) {

        String searchTerm = "(";
        if (type != null && !"".equals(type)) {
            searchTerm += " type:\"" + type + "\" AND ";
        }
        // we support still the deprecated txtworkflowgroup
        searchTerm += " ($workflowgroup:\"" + name + "\" OR txtworkflowgroup:\"" + name + "\") )";
        try {
            return documentService.find(searchTerm, pageSize, pageIndex, sortBy, sortReverse);
        } catch (QueryException e) {
            logger.severe("getWorkListByGroup - invalid param: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns a collection of workitems belonging to a specified $taskID defined by
     * the workflow model. The behaivor is simmilar to the method getWorkList.
     * 
     * @param aID         = $taskID for the workitems to be returned.
     * @param pageSize    = optional page count (default 20)
     * @param pageIndex   = optional start position
     * @param type        = defines the type property of the workitems to be
     *                    returnd. can be null
     * @param sortBy      -optional field to sort the result
     * @param sortReverse - optional sort direction
     * 
     * @return List of workitems
     * 
     */
    public List<ItemCollection> getWorkListByProcessID(int aid, String type, int pageSize, int pageIndex, String sortBy,
            boolean sortReverse) {

        String searchTerm = "(";
        if (type != null && !"".equals(type)) {
            searchTerm += " type:\"" + type + "\" AND ";
        }
        // need to be fixed during slow migration issue #384
        searchTerm += " $processid:\"" + aid + "\" )";
        try {
            return documentService.find(searchTerm, pageSize, pageIndex, sortBy, sortReverse);
        } catch (QueryException e) {
            logger.severe("getWorkListByProcessID - invalid param: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns a collection of workitems belonging to a specified workitem
     * identified by the attribute $UniqueIDRef.
     * 
     * The behaivor of this Mehtod is simmilar to the method getWorkList.
     * 
     * @param aref        A unique reference to another workitem inside a database *
     * @param pageSize    = optional page count (default 20)
     * @param pageIndex   = optional start position
     * @param type        = defines the type property of the workitems to be
     *                    returnd. can be null
     * @param sortBy      -optional field to sort the result
     * @param sortReverse - optional sort direction
     * 
     * @return List of workitems
     */
    public List<ItemCollection> getWorkListByRef(String aref, String type, int pageSize, int pageIndex, String sortBy,
            boolean sortReverse) {

        String searchTerm = "(";
        if (type != null && !"".equals(type)) {
            searchTerm += " type:\"" + type + "\" AND ";
        }
        searchTerm += " $uniqueidref:\"" + aref + "\" )";
        try {
            return documentService.find(searchTerm, pageSize, pageIndex, sortBy, sortReverse);
        } catch (QueryException e) {
            logger.severe("getWorkListByRef - invalid param: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns a collection of all workitems belonging to a specified workitem
     * identified by the attribute $UniqueIDRef.
     * 
     * @return List of workitems
     */
    public List<ItemCollection> getWorkListByRef(String aref) {
        return getWorkListByRef(aref, null, 0, 0, null, false);
    }

    /**
     * This returns a list of workflow events assigned to a given workitem. The
     * method evaluates the events for the current $modelversion and $taskid. The
     * result list is filtered by the properties 'keypublicresult' and
     * 'keyRestrictedVisibility'.
     * 
     * If the property keyRestrictedVisibility exits the method test if the current
     * username is listed in one of the namefields.
     * 
     * If the current user is in the role 'org.imixs.ACCESSLEVEL.MANAGERACCESS' the
     * property keyRestrictedVisibility will be ignored.
     * 
     * @see imixs-bpmn
     * @param workitem
     * @return
     * @throws ModelException
     */
    @SuppressWarnings("unchecked")
    public List<ItemCollection> getEvents(ItemCollection workitem) throws ModelException {
        List<ItemCollection> result = new ArrayList<ItemCollection>();
        int processID = workitem.getTaskID();
        // verify if version is valid
        Model model = modelService.getModelByWorkitem(workitem);

        List<ItemCollection> eventList = model.findAllEventsByTask(processID);

        String username = getUserName();
        boolean bManagerAccess = ctx.isCallerInRole(DocumentService.ACCESSLEVEL_MANAGERACCESS);

        // now filter events which are not public (keypublicresult==false) or
        // restricted for current user (keyRestrictedVisibility).
        for (ItemCollection event : eventList) {
            // test keypublicresult==false

            // ad only activities with userControlled != No
            if ("0".equals(event.getItemValueString("keypublicresult"))) {
                continue;
            }

            // test user access level
            List<String> readAccessList = event.getItemValue("$readaccess");
            if (!bManagerAccess && !readAccessList.isEmpty()) {
                /**
                 * check read access for current user
                 */
                boolean accessGranted = false;
                // get user name list
                List<String> auserNameList = getUserNameList();

                // check each read access
                for (String aReadAccess : readAccessList) {
                    if (aReadAccess != null && !aReadAccess.isEmpty()) {
                        if (auserNameList.indexOf(aReadAccess) > -1) {
                            accessGranted = true;
                            break;
                        }
                    }
                }
                if (!accessGranted) {
                    // user has no read access!
                    continue;
                }
            }

            // test RestrictedVisibility
            List<String> restrictedList = event.getItemValue("keyRestrictedVisibility");
            if (!bManagerAccess && !restrictedList.isEmpty()) {
                // test each item for the current user name...
                List<String> totalNameList = new ArrayList<String>();
                for (String itemName : restrictedList) {
                    totalNameList.addAll(workitem.getItemValue(itemName));
                }
                // remove null and empty values....
                totalNameList.removeAll(Collections.singleton(null));
                totalNameList.removeAll(Collections.singleton(""));
                if (!totalNameList.isEmpty() && !totalNameList.contains(username)) {
                    // event is not visible for current user!
                    continue;
                }
            }
            result.add(event);
        }

        return result;

    }

    /**
     * This method processes a workItem by the WorkflowKernel and saves the workitem
     * after the processing was finished successful. The workitem have to provide at
     * least the properties '$modelversion', '$taskid' and '$eventid'
     * <p>
     * Before the method starts processing the workitem, the method load the current
     * instance of the given workitem and compares the property $taskID. If it is
     * not equal the method throws an ProcessingErrorException.
     * <p>
     * After the workitem was processed successful, the method verifies the property
     * $workitemList. If this property holds a list of entities these entities will
     * be saved and the property will be removed automatically.
     * <p>
     * The method provides a observer pattern for plugins to get called during the
     * processing phase.
     * 
     * @param workitem - the workItem to be processed
     * @return updated version of the processed workItem
     * @throws AccessDeniedException    - thrown if the user has insufficient access
     *                                  to update the workItem
     * @throws ProcessingErrorException - thrown if the workitem could not be
     *                                  processed by the workflowKernel
     * @throws PluginException          - thrown if processing by a plugin fails
     * @throws ModelException
     */
    public ItemCollection processWorkItem(ItemCollection workitem)
            throws AccessDeniedException, ProcessingErrorException, PluginException, ModelException {
        boolean debug = logger.isLoggable(Level.FINE);
        long lStartTime = System.currentTimeMillis();

        if (workitem == null)
            throw new ProcessingErrorException(WorkflowService.class.getSimpleName(),
                    ProcessingErrorException.INVALID_WORKITEM, "workitem Is Null!");

        // fire event
        if (processingEvents != null) {
            processingEvents.fire(new ProcessingEvent(workitem, ProcessingEvent.BEFORE_PROCESS));
        } else {
            logger.warning("CDI Support is missing - ProcessingEvents Not Supported!");
        }
        // load current instance of this workitem if a unqiueID is provided
        if (!workitem.getUniqueID().isEmpty()) {
            // try to load the instance
            ItemCollection currentInstance = this.getWorkItem(workitem.getUniqueID());
            // Instance successful loaded ?
            if (currentInstance != null) {
                // test for author access
                if (!currentInstance.getItemValueBoolean(DocumentService.ISAUTHOR)) {
                    throw new AccessDeniedException(AccessDeniedException.OPERATION_NOTALLOWED, "$uniqueid: "
                            + workitem.getItemValueInteger(WorkflowKernel.UNIQUEID) + " - No Author Access!");
                }
                // test if $taskID matches current instance
                if (workitem.getTaskID() > 0 && currentInstance.getTaskID() != workitem.getTaskID()) {
                    throw new ProcessingErrorException(WorkflowService.class.getSimpleName(),
                            ProcessingErrorException.INVALID_PROCESSID,
                            "$uniqueid: " + workitem.getItemValueInteger(WorkflowKernel.UNIQUEID) + " - $taskid="
                                    + workitem.getTaskID() + " Did Not Match Expected $taskid="
                                    + currentInstance.getTaskID());
                }
                // merge workitem into current instance (issue #86, issue #507)
                // an instance of this WorkItem still exists! so we update the new
                // values....
                workitem.mergeItems(currentInstance.getAllItems());

            } else {
                // In case we have a $UniqueId but did not found an matching workitem
                // and the workitem miss a valid model assignment than
                // processing is not possible - OPERATION_NOTALLOWED

                if ((workitem.getTaskID() <= 0) || (workitem.getEventID() <= 0)
                        || (workitem.getModelVersion().isEmpty() && workitem.getWorkflowGroup().isEmpty())) {
                    // user has no read access -> throw AccessDeniedException
                    throw new InvalidAccessException(InvalidAccessException.OPERATION_NOTALLOWED,
                            "$uniqueid: " + workitem.getItemValueInteger(WorkflowKernel.UNIQUEID)
                                    + " - Insufficient Data or Lack Of Permission!");
                }

            }
        }

        // verify type attribute
        if ("".equals(workitem.getType())) {
            workitem.replaceItemValue("type", DEFAULT_TYPE);
        }

        /*
         * Lookup current processEntity. If not available update model to latest
         * matching model version
         */
        Model model = null;
        try {
            model = this.getModelManager().getModelByWorkitem(workitem);
        } catch (ModelException e) {
            throw new ProcessingErrorException(WorkflowService.class.getSimpleName(),
                    ProcessingErrorException.INVALID_PROCESSID, e.getMessage(), e);
        }

        WorkflowKernel workflowkernel = new WorkflowKernel(this);
        // register plugins...
        registerPlugins(workflowkernel, model);
        // register adapters.....
        registerAdapters(workflowkernel);
        // udpate workitem metadata...
        updateMetadata(workitem);

        // now process the workitem
        try {
            long lKernelTime = System.currentTimeMillis();
            workitem = workflowkernel.process(workitem);
            if (debug) {
                logger.fine("...WorkflowKernel processing time=" + (System.currentTimeMillis() - lKernelTime) + "ms");
            }
        } catch (PluginException pe) {
            // if a plugin exception occurs we roll back the transaction.
            logger.severe("processing workitem '" + workitem.getItemValueString(WorkflowKernel.UNIQUEID)
                    + " failed, rollback transaction...");
            ctx.setRollbackOnly();
            throw pe;
        }

        // fire event
        if (processingEvents != null) {
            processingEvents.fire(new ProcessingEvent(workitem, ProcessingEvent.AFTER_PROCESS));
        }
        // Now fire also events for all split versions.....
        List<ItemCollection> splitWorkitems = workflowkernel.getSplitWorkitems();
        for (ItemCollection splitWorkitemm : splitWorkitems) {
            // fire event
            if (processingEvents != null) {
                processingEvents.fire(new ProcessingEvent(splitWorkitemm, ProcessingEvent.AFTER_PROCESS));
            }
            documentService.save(splitWorkitemm);
        }

        workitem = documentService.save(workitem);
        if (debug) {
            logger.fine("...total processing time=" + (System.currentTimeMillis() - lStartTime) + "ms");
        }
        return workitem;
    }

    /**
     * This method processes a workItem based on a given event.
     * 
     * @see method ItemCollection processWorkItem(ItemCollection workitem)
     * 
     * @param workitem - the workItem to be processed
     * @param event    - event object
     * @return updated version of the processed workItem
     * @throws AccessDeniedException    - thrown if the user has insufficient access
     *                                  to update the workItem
     * @throws ProcessingErrorException - thrown if the workitem could not be
     *                                  processed by the workflowKernel
     * @throws PluginException          - thrown if processing by a plugin fails
     * @throws ModelException
     **/
    public ItemCollection processWorkItem(ItemCollection workitem, ItemCollection event)
            throws AccessDeniedException, ProcessingErrorException, PluginException, ModelException {

        return processWorkItem(workitem, event.getItemValueInteger("numactivityid"));
    }

    /**
     * This method processes a workItem based on a given event.
     * 
     * @see method ItemCollection processWorkItem(ItemCollection workitem)
     * 
     * @param workitem - the workItem to be processed
     * @param event    - event object
     * @return updated version of the processed workItem
     * @throws AccessDeniedException    - thrown if the user has insufficient access
     *                                  to update the workItem
     * @throws ProcessingErrorException - thrown if the workitem could not be
     *                                  processed by the workflowKernel
     * @throws PluginException          - thrown if processing by a plugin fails
     * @throws ModelException
     **/
    public ItemCollection processWorkItem(ItemCollection workitem, int eventID)
            throws AccessDeniedException, ProcessingErrorException, PluginException, ModelException {

        workitem.setEventID(eventID);
        return processWorkItem(workitem);
    }

    /**
     * This method processes a workitem in a new transaction.
     * 
     * @throws ModelException
     * @throws PluginException
     * @throws ProcessingErrorException
     * @throws AccessDeniedException
     * 
     */
    @TransactionAttribute(value = TransactionAttributeType.REQUIRES_NEW)
    public ItemCollection processWorkItemByNewTransaction(ItemCollection workitem)
            throws AccessDeniedException, ProcessingErrorException, PluginException, ModelException {
        boolean debug = logger.isLoggable(Level.FINE);
        if (debug) {
            logger.finest(" ....processing workitem by by new transaction...");
        }
        return processWorkItem(workitem);
    }

    public void removeWorkItem(ItemCollection aworkitem) throws AccessDeniedException {
        documentService.remove(aworkitem);
    }

    /**
     * This Method returns the modelManager Instance. The current ModelVersion is
     * automatically updated during the Method updateProfileEntity which is called
     * from the processWorktiem method.
     * 
     */
    public ModelManager getModelManager() {
        return modelService;
    }

    /**
     * Returns an instance of the EJB session context.
     * 
     * @return
     */
    public SessionContext getSessionContext() {
        return ctx;
    }

    /**
     * Returns an instance of the DocumentService EJB.
     * 
     * @return
     */
    public DocumentService getDocumentService() {
        return documentService;
    }

    /**
     * Returns an instance of the ReportService EJB.
     * 
     * @return
     */
    public ReportService getReportService() {
        return reportService;
    }

    /**
     * Obtain the java.security.Principal that identifies the caller and returns the
     * name of this principal.
     * 
     * @return the user name
     */
    public String getUserName() {
        return ctx.getCallerPrincipal().getName();

    }

    /**
     * Test if the caller has a given security role.
     * 
     * @param rolename
     * @return true if user is in role
     */
    public boolean isUserInRole(String rolename) {
        try {
            return ctx.isCallerInRole(rolename);
        } catch (Exception e) {
            // avoid a exception for a role request which is not defined
            return false;
        }
    }

    /**
     * This method returns a list of user names, roles and application groups the
     * caller belongs to.
     * 
     * @return
     */
    public List<String> getUserNameList() {
        return documentService.getUserNameList();
    }

    /**
     * The method adaptText can be called to replace predefined xml tags included in
     * a text with custom values. The method fires a CDI event to inform
     * TextAdapterServices to parse and adapt a given text fragment.
     * 
     * @param text
     * @param documentContext
     * @return
     * @throws PluginException
     */
    public String adaptText(String text, ItemCollection documentContext) throws PluginException {
        // fire event
        if (textEvents != null) {
            TextEvent event = new TextEvent(text, documentContext);
            textEvents.fire(event);
            text = event.getText();
        } else {
            logger.warning("CDI Support is missing - TextEvent wil not be fired");
        }
        return text;
    }

    /**
     * The method adaptTextList can be called to replace a text with custom values.
     * The method fires a CDI event to inform TextAdapterServices to parse and adapt
     * a given text fragment. The method expects a textList result.
     * 
     * @param text
     * @param documentContext
     * @return
     * @throws PluginException
     */
    public List<String> adaptTextList(String text, ItemCollection documentContext) throws PluginException {
        // fire event
        if (textEvents != null) {
            TextEvent event = new TextEvent(text, documentContext);
            textEvents.fire(event);
            return event.getTextList();
        } else {
            logger.warning("CDI Support is missing - TextEvent wil not be fired");
        }
        // no result return default
        List<String> textList = new ArrayList<String>();
        textList.add(text);
        return textList;
    }

    /**
     * The method evaluates the WorkflowResult for a given BPMN event and returns a
     * ItemColleciton containing all item values of a specified tag name. Each tag
     * definition of a WorkflowResult contains a name and a optional list of
     * additional attributes. The method generates a item for each content element
     * and attribute value. <br>
     * e.g. <item name="comment" ignore="true">text</item> <br>
     * will result in the attributes 'comment' with value 'text' and
     * 'comment.ignore' with the value 'true'
     * <p>
     * Also embedded itemVaues can be resolved (resolveItemValues=true):
     * <p>
     * {@code
     * 		<somedata>ABC<itemvalue>$uniqueid</itemvalue></somedata>
     * }
     * <p>
     * This example will result in a new item 'somedata' with the $uniqueid prefixed
     * with 'ABC'
     * 
     * @see https://stackoverflow.com/questions/1732348/regex-match-open-tags-except-xhtml-self-contained-tags
     * @param event
     * @param tag               - tag to be evaluated
     * @param documentContext
     * @param resolveItemValues - if true, itemValue tags will be resolved.
     * @return eval itemCollection or null if no tags are contained in the workflow
     *         result.
     * @throws PluginException if the xml structure is invalid
     */
    public ItemCollection evalWorkflowResult(ItemCollection event, String tag, ItemCollection documentContext,
            boolean resolveItemValues) throws PluginException {
        boolean debug = logger.isLoggable(Level.FINE);
        ItemCollection result = new ItemCollection();
        String workflowResult = event.getItemValueString("txtActivityResult");
        if (workflowResult.trim().isEmpty()) {
            return null;
        }
        if (tag == null || tag.isEmpty()) {
            logger.warning("cannot eval workflow result - no tag name specified. Verify model!");
            return null;
        }

        // if no <tag exists we skip the evaluation...
        if (workflowResult.indexOf("<" + tag) == -1) {
            return null;
        }

        // replace dynamic values?
        if (resolveItemValues) {
            workflowResult = adaptText(workflowResult, documentContext);
        }

        boolean invalidPattern = false;
        // Fast first test if the tag really exists....
        Pattern patternSimple = Pattern.compile("<" + tag + " (.*?)>(.*?)|<" + tag + " (.*?)./>", Pattern.DOTALL);
        Matcher matcherSimple = patternSimple.matcher(workflowResult);
        if (matcherSimple.find()) {
            invalidPattern = true;
            // we found the starting tag.....

            // Extract all tags with attributes using regex (including empty tags)
            // see also:
            // https://stackoverflow.com/questions/1732348/regex-match-open-tags-except-xhtml-self-contained-tags
            // e.g. <item(.*?)>(.*?)</item>|<item(.*?)./>
            Pattern pattern = Pattern.compile("(?s)(?:(<" + tag + "(?>\\b(?:\".*?\"|'.*?'|[^>]*?)*>)(?<=/>))|(<" + tag
                    + "(?>\\b(?:\".*?\"|'.*?'|[^>]*?)*>)(?<!/>))(.*?)(</" + tag + "\\s*>))", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(workflowResult);
            while (matcher.find()) {
                invalidPattern = false;
                // we expect up to 4 different result groups
                // group 0 contains complete tag string
                // groups 1 or 2 contain the attributes

                String content = "";
                String attributes = matcher.group(1);
                if (attributes == null) {
                    attributes = matcher.group(2);
                    content = matcher.group(3);
                } else {
                    content = matcher.group(2);
                }

                if (content == null) {
                    content = "";
                }

                // now extract the attributes to verify the tag name..
                if (attributes != null && !attributes.isEmpty()) {
                    // parse attributes...
                    String spattern = "(\\S+)=[\"']?((?:.(?![\"']?\\s+(?:\\S+)=|[>\"']))+.)[\"']?";
                    Pattern attributePattern = Pattern.compile(spattern);
                    Matcher attributeMatcher = attributePattern.matcher(attributes);
                    Map<String, String> attrMap = new HashMap<String, String>();
                    while (attributeMatcher.find()) {
                        String attrName = attributeMatcher.group(1); // name
                        String attrValue = attributeMatcher.group(2); // value
                        attrMap.put(attrName, attrValue);
                    }

                    String tagName = attrMap.get("name");
                    if (tagName == null) {
                        throw new PluginException(ResultPlugin.class.getSimpleName(), INVALID_TAG_FORMAT,
                                "<" + tag + "> tag contains no name attribute.");
                    }

                    // now add optional attributes if available
                    for (String attrName : attrMap.keySet()) {
                        // we need to skip the 'name' attribute
                        if (!"name".equals(attrName)) {
                            result.appendItemValue(tagName + "." + attrName, attrMap.get(attrName));
                        }
                    }

                    // test if the type attribute was provided to convert content?
                    String sType = result.getItemValueString(tagName + ".type");
                    String sFormat = result.getItemValueString(tagName + ".format");
                    if (!sType.isEmpty()) {
                        // convert content type
                        if ("boolean".equalsIgnoreCase(sType)) {
                            result.appendItemValue(tagName, Boolean.valueOf(content));
                        } else if ("integer".equalsIgnoreCase(sType)) {
                            try {
                                result.appendItemValue(tagName, Integer.valueOf(content));
                            } catch (NumberFormatException e) {
                                // append 0 value
                                result.appendItemValue(tagName, new Integer(0));
                            }
                        } else if ("double".equalsIgnoreCase(sType)) {
                            try {
                                result.appendItemValue(tagName, Double.valueOf(content));
                            } catch (NumberFormatException e) {
                                // append 0 value
                                result.appendItemValue(tagName, new Double(0));
                            }
                        } else if ("float".equalsIgnoreCase(sType)) {
                            try {
                                result.appendItemValue(tagName, Float.valueOf(content));
                            } catch (NumberFormatException e) {
                                // append 0 value
                                result.appendItemValue(tagName, new Float(0));
                            }
                        } else if ("long".equalsIgnoreCase(sType)) {
                            try {
                                result.appendItemValue(tagName, Long.valueOf(content));
                            } catch (NumberFormatException e) {
                                // append 0 value
                                result.appendItemValue(tagName, new Long(0));
                            }
                        } else if ("date".equalsIgnoreCase(sType)) {
                            if (content == null || content.isEmpty()) {
                                // no value available - no op!
                                if (debug) {
                                    logger.finer("......can not convert empty string into date object");
                                }
                            } else {
                                // convert content value to date object
                                try {
                                    if (debug) {
                                        logger.finer("......convert string into date object");
                                    }
                                    Date dateResult = null;
                                    if (sFormat == null || sFormat.isEmpty()) {
                                        // use standard format short/short
                                        dateResult = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                                .parse(content);
                                    } else {
                                        // use given formatter (see: TextItemValueAdapter)
                                        DateFormat dateFormat = new SimpleDateFormat(sFormat);
                                        dateResult = dateFormat.parse(content);
                                    }
                                    result.appendItemValue(tagName, dateResult);
                                } catch (ParseException e) {
                                    if (debug) {
                                        logger.finer("failed to convert string into date object: " + e.getMessage());
                                    }
                                }
                            }

                        } else
                            // no type conversion
                            result.appendItemValue(tagName, content);
                    } else {
                        // no type definition
                        result.appendItemValue(tagName, content);
                    }

                } else {
                    throw new PluginException(ResultPlugin.class.getSimpleName(), INVALID_TAG_FORMAT,
                            "<" + tag + "> tag contains no name attribute.");

                }
            }
        }

        // test for general invalid format
        if (invalidPattern) {
            throw new PluginException(ResultPlugin.class.getSimpleName(), INVALID_TAG_FORMAT,
                    "invalid <" + tag + "> tag format in workflowResult: " + workflowResult + "  , expected format is <"
                            + tag + " name=\"...\">...</item> ");
        }
        return result;
    }

    /**
     * The method evaluates the WorkflowResult for a given BPMN event and returns a
     * ItemColleciton containing all item values of a specified tag name. Each tag
     * definition of a WorkflowResult contains a name and a optional list of
     * additional attributes. The method generates a item for each content element
     * and attribute value. <br>
     * e.g. <item name="comment" ignore="true">text</item> <br>
     * will result in the attributes 'comment' with value 'text' and
     * 'comment.ignore' with the value 'true'
     * <p>
     * Also embedded itemVaues can be resolved (resolveItemValues=true):
     * <p>
     * {@code
     *      <somedata>ABC<itemvalue>$uniqueid</itemvalue></somedata>
     * }
     * <p>
     * This example will result in a new item 'somedata' with the $uniqueid prefixed
     * with 'ABC'
     * 
     * @see evalWorkflowResult(ItemCollection event, String tag, ItemCollection
     *      documentContext,boolean resolveItemValues)
     * @param event
     * @param tag             - tag to be evaluated
     * @param documentContext
     * @return
     * @throws PluginException
     */
    public ItemCollection evalWorkflowResult(ItemCollection event, String tag, ItemCollection documentContext)
            throws PluginException {
        return evalWorkflowResult(event, tag, documentContext, true);
    }

    @Deprecated
    public ItemCollection evalWorkflowResult(ItemCollection event, ItemCollection documentContext,
            boolean resolveItemValues) throws PluginException {
        logger.warning(
                "Method call evalWorkflowResult(event, workitem, resolve) is deprecated, use method evalWorkflowResult(event, tag, workitem, resolve) instead!");
        return this.evalWorkflowResult(event, "item", documentContext, resolveItemValues);
    }

    @Deprecated
    public ItemCollection evalWorkflowResult(ItemCollection event, ItemCollection documentContext)
            throws PluginException {
        logger.warning(
                "Method call evalWorkflowResult(event, workitem) is deprecated, use method evalWorkflowResult(event, tag, workitem) instead!");
        return this.evalWorkflowResult(event, "item", documentContext);
    }

    /**
     * The method evaluates the next task for a process instance (workitem) based on
     * the current model definition. A Workitem must at least provide the properties
     * $TASKID and $EVENTID.
     * <p>
     * During the evaluation life-cycle more than one events can be evaluated. This
     * depends on the model definition which can define follow-up-events,
     * split-events and conditional events.
     * <p>
     * The method did not persist the process instance or execute any plugin or
     * adapter classes.
     * 
     * @return Task entity
     * @throws PluginException
     * @throws ModelException
     */
    public ItemCollection evalNextTask(ItemCollection documentContext) throws PluginException, ModelException {
        WorkflowKernel workflowkernel = new WorkflowKernel(this);
        int taskID = workflowkernel.eval(documentContext);
        ItemCollection task = this.getModelManager().getModel(documentContext.getModelVersion()).getTask(taskID);
        return task;
    }

    /**
     * This method register all plugin classes listed in the model profile
     * 
     * @throws PluginException
     */
    @SuppressWarnings("unchecked")
    protected void registerPlugins(WorkflowKernel workflowkernel, Model model) throws PluginException {
        boolean debug = logger.isLoggable(Level.FINE);
        // Fetch the current Profile Entity for this version.
        ItemCollection profile = model.getDefinition();

        // register plugins defined in the environment.profile ....
        List<String> vPlugins = (List<String>) profile.getItemValue("txtPlugins");
        for (int i = 0; i < vPlugins.size(); i++) {
            String aPluginClassName = vPlugins.get(i);

            Plugin aPlugin = findPluginByName(aPluginClassName);
            // aPlugin=null;
            if (aPlugin != null) {
                // register injected CDI Plugin
                if (debug) {
                    logger.finest("......register CDI plugin class: " + aPluginClassName + "...");
                }
                workflowkernel.registerPlugin(aPlugin);
            } else {
                // register plugin by class name
                workflowkernel.registerPlugin(aPluginClassName);
            }
        }
    }

    protected void registerAdapters(WorkflowKernel workflowkernel) {
        boolean debug = logger.isLoggable(Level.FINE);
        if (debug && (adapters == null || !adapters.iterator().hasNext())) {
            logger.finest("......no CDI Adapters injected");
        } else {
            // iterate over all injected adapters....
            for (Adapter adapter : this.adapters) {
                if (debug) {
                    logger.finest("......register CDI Adapter class '" + adapter.getClass().getName() + "'");
                }
                workflowkernel.registerAdapter(adapter);
            }
        }
    }

    /**
     * This method updates the workitem metadata. The following items will be
     * updated:
     * 
     * <ul>
     * <li>$creator</li>
     * <li>$editor</li>
     * <li>$lasteditor</li>
     * <li>$participants</li>
     * </ul>
     * <p>
     * The method also migrates deprected items.
     * 
     * @param workitem
     */
    protected void updateMetadata(ItemCollection workitem) {

        // identify Caller and update CurrentEditor
        String nameEditor;
        nameEditor = ctx.getCallerPrincipal().getName();

        // add namCreator if empty
        // migrate $creator (Backward compatibility)
        if (workitem.getItemValueString("$creator").isEmpty() && !workitem.getItemValueString("namCreator").isEmpty()) {
            workitem.replaceItemValue("$creator", workitem.getItemValue("namCreator"));
        }

        if (workitem.getItemValueString("$creator").isEmpty()) {
            workitem.replaceItemValue("$creator", nameEditor);
            // support deprecated fieldname
            workitem.replaceItemValue("namCreator", nameEditor);
        }

        // update namLastEditor only if current editor has changed
        if (!nameEditor.equals(workitem.getItemValueString("$editor"))
                && !workitem.getItemValueString("$editor").isEmpty()) {
            workitem.replaceItemValue("$lasteditor", workitem.getItemValueString("$editor"));
            // deprecated
            workitem.replaceItemValue("namlasteditor", workitem.getItemValueString("$editor"));
        }

        // update $editor
        workitem.replaceItemValue("$editor", nameEditor);
        // deprecated
        workitem.replaceItemValue("namcurrenteditor", nameEditor);
    }

    /**
     * This method returns an injected Plugin by name or null if no plugin with the
     * requested class name is injected.
     * 
     * @param pluginClassName
     * @return plugin class or null if not found
     */
    private Plugin findPluginByName(String pluginClassName) {
        if (pluginClassName == null || pluginClassName.isEmpty())
            return null;
        boolean debug = logger.isLoggable(Level.FINE);

        if (plugins == null || !plugins.iterator().hasNext()) {
            if (debug) {
                logger.finest("......no CDI plugins injected");
            }
            return null;
        }
        // iterate over all injected plugins....
        for (Plugin plugin : this.plugins) {
            if (plugin.getClass().getName().equals(pluginClassName)) {
                if (debug) {
                    logger.finest("......CDI plugin '" + pluginClassName + "' successful injected");
                }
                return plugin;
            }
        }

        return null;
    }
}
