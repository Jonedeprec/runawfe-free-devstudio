package ru.runa.gpd.lang.model;

import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.draw2d.geometry.Rectangle;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IActionFilter;
import org.eclipse.ui.views.properties.IPropertyDescriptor;
import org.eclipse.ui.views.properties.IPropertySource;
import org.eclipse.ui.views.properties.PropertyDescriptor;
import org.eclipse.ui.views.properties.TextPropertyDescriptor;
import ru.runa.gpd.Localization;
import ru.runa.gpd.PluginLogger;
import ru.runa.gpd.PropertyNames;
import ru.runa.gpd.extension.DelegableProvider;
import ru.runa.gpd.extension.HandlerRegistry;
import ru.runa.gpd.lang.Language;
import ru.runa.gpd.lang.NodeRegistry;
import ru.runa.gpd.lang.NodeTypeDefinition;
import ru.runa.gpd.lang.ValidationError;
import ru.runa.gpd.lang.model.bpmn.IBoundaryEventCapable;
import ru.runa.gpd.lang.model.bpmn.IBoundaryEventContainer;
import ru.runa.gpd.lang.model.bpmn.TextAnnotation;
import ru.runa.gpd.lang.model.jpdl.ActionContainer;
import ru.runa.gpd.property.DelegableClassPropertyDescriptor;
import ru.runa.gpd.property.DelegableConfPropertyDescriptor;
import ru.runa.gpd.property.DescribablePropertyDescriptor;
import ru.runa.gpd.property.DurationPropertyDescriptor;
import ru.runa.gpd.property.TimerActionPropertyDescriptor;
import ru.runa.gpd.settings.CommonPreferencePage;
import ru.runa.gpd.ui.enhancement.DialogEnhancement;
import ru.runa.gpd.ui.enhancement.DocxDialogEnhancement;
import ru.runa.gpd.ui.enhancement.DocxDialogEnhancementMode;
import ru.runa.gpd.util.EmbeddedFileUtils;
import ru.runa.gpd.util.EventSupport;
import ru.runa.gpd.util.VariableUtils;

@SuppressWarnings("unchecked")
public abstract class GraphElement extends EventSupport
        implements IPropertySource, PropertyNames, IActionFilter, VariableContainer, ProcessDefinitionAware {
	// TODO rm3004
    private final Set<PropertyChangeListener> delegatedListeners = new HashSet<>();
    private GraphElement parent;
    private GraphElement uiParentContainer;
    private final List<GraphElement> children = new ArrayList<GraphElement>();
    private Rectangle constraint;
    private String id;

    public String getId() {
        return id;
    }

    public void setId(String nodeId) {
        this.id = nodeId;
    }

    /**
     * @return parent container or <code>null</code> in case of {@link ProcessDefinition}
     */
    public GraphElement getUiParentContainer() {
        return uiParentContainer;
    }

    public void setUiParentContainer(GraphElement uiParentContainer) {
        this.uiParentContainer = uiParentContainer;
    }

    @Override
    public boolean testAttribute(Object target, String name, String value) {
        if ("language".equals(name)) {
            return Objects.equal(value, getProcessDefinition().getLanguage().name().toLowerCase());
        }
        if ("delegableEditHandler".equals(name) || "delegableEditConfiguration".equals(name)) {
            return Objects.equal(value, String.valueOf(isDelegable()));
        }
        if ("regulationsEnabled".equals(name)) {
            return Objects.equal(value, String.valueOf(CommonPreferencePage.isRegulationsMenuItemsEnabled()));
        }
        return false;
    }

    public void setDelegatedListener(PropertyChangeListener delegatedListener) {
        if (delegatedListener != null) {
            this.delegatedListeners.add(delegatedListener);
            addPropertyChangeListener(delegatedListener);
            for (GraphElement child : getChildren(GraphElement.class)) {
                child.setDelegatedListener(delegatedListener);
            }
        }
    }

    public void unsetDelegatedListener(PropertyChangeListener delegatedListener) {
        if (delegatedListener != null) {
            removePropertyChangeListener(delegatedListener);
            for (GraphElement child : getChildren(GraphElement.class)) {
                child.unsetDelegatedListener(delegatedListener);
            }
            this.delegatedListeners.remove(delegatedListener);
        }
    }

    public Rectangle getConstraint() {
        return constraint;
    }

    public void setDirty() {
        ProcessDefinition processDefinition = getProcessDefinition();
        if (processDefinition != null) {
            processDefinition.setDirty(true);
        }
    }

    public void setConstraint(Rectangle newConstraint) {
        if (!Objects.equal(this.constraint, newConstraint)) {
            Rectangle oldConstraint = this.constraint;
            this.constraint = newConstraint;
            firePropertyChange(NODE_BOUNDS_RESIZED, oldConstraint, newConstraint);
            if (this.constraint != null && this instanceof IBoundaryEventContainer) {
                for (GraphElement element : children) {
                    if (element.getConstraint() != null && element instanceof IBoundaryEventCapable
                            && ((IBoundaryEventCapable) element).isBoundaryEvent()) {
                        ((IBoundaryEventCapable) element).updateBoundaryEventConstraint();
                    }
                }
            }
        }
    }

    @Override
    public ProcessDefinition getProcessDefinition() {
        if (this instanceof ProcessDefinition) {
            return (ProcessDefinition) this;
        }
        if (parent == null) {
            return null;
        }
        return parent.getProcessDefinition();
    }

    public void validate(List<ValidationError> errors, IFile definitionFile) {
        if (isDelegable()) {
            Delegable delegable = (Delegable) this;
            DelegableProvider provider = HandlerRegistry.getProvider(delegationClassName);
            if (delegationClassName == null || delegationClassName.length() == 0) {
                errors.add(ValidationError.createLocalizedError(this, "delegationClassName.empty"));
            } else if (!HandlerRegistry.getInstance().isArtifactRegistered(delegable.getDelegationType(), delegationClassName)) {
                errors.add(ValidationError.createLocalizedWarning(this, "delegationClassName.classNotFound"));
            } else {
                try {
                    if (!provider.validateValue(delegable, errors)) {
                        errors.add(ValidationError.createLocalizedError(this, "delegable.invalidConfiguration"));
                    }
                } catch (Exception e) {
                    errors.add(ValidationError.createLocalizedError(this, "delegable.invalidConfigurationWithError", e));
                    PluginLogger.logErrorWithoutDialog("Script configuration '" + getDelegationConfiguration() + "' error: " + e);
                }

                if (delegationClassName.equals(DocxDialogEnhancementMode.DocxHandlerID)) {
                    Object obj = DialogEnhancement.getConfigurationValue(delegable, DocxDialogEnhancementMode.InputPathId);
                    String embeddedDocxTemplateFileName = null != obj && obj instanceof String ? (String) obj : "";
                    if (!Strings.isNullOrEmpty(embeddedDocxTemplateFileName) && EmbeddedFileUtils.isProcessFile(embeddedDocxTemplateFileName)) {
                        List<String> docxErrors = Lists.newArrayList();
                        List<Delegable> docxErrorSources = Lists.newArrayList();
                        DocxDialogEnhancement.checkScriptTaskParametersWithDocxTemplate(delegable,
                                EmbeddedFileUtils.getProcessFileName(embeddedDocxTemplateFileName), docxErrors, docxErrorSources, null);
                        if (docxErrors.size() > 0 && docxErrors.size() == docxErrorSources.size()) {
                            ListIterator<String> iterator = docxErrors.listIterator();
                            ListIterator<Delegable> iterator2 = docxErrorSources.listIterator();
                            while (iterator.hasNext()) {
                                Delegable delegable2 = iterator2.next();
                                errors.add(ValidationError.createError(delegable2 instanceof GraphElement ? (GraphElement) delegable2 : this,
                                        iterator.next()));
                            }
                        }
                    }
                }
            }
        }
        for (GraphElement element : children) {
            try {
                element.validate(errors, definitionFile);
            } catch (Exception e) {
                PluginLogger.logErrorWithoutDialog("validation error", e);
                errors.add(ValidationError.createLocalizedWarning(element, "error", e));
            }
        }
    }

    public NodeTypeDefinition getTypeDefinition() {
        return NodeRegistry.getNodeTypeDefinition(getClass());
    }

    public GraphElement getParent() {
        return parent;
    }

    public void setParent(GraphElement parent) {
        this.parent = parent;
    }

    public void removeChild(GraphElement child) {
        if (child instanceof Delegable) {
            DelegableProvider provider = HandlerRegistry.getProvider(child.getDelegationClassName());
            provider.onDelete((Delegable) child);
        }
        children.remove(child);
        firePropertyChange(NODE_REMOVED, child, null);
        firePropertyChange(PROPERTY_CHILDREN_CHANGED, child, null);
        for (PropertyChangeListener delegatedListener : child.delegatedListeners) {
            child.removePropertyChangeListener(delegatedListener);
        }
        if (child instanceof Node) {
            ((Node) child).updateRegulationsPropertiesOnDelete();
        }
    }

    public int removeAction(Action action) {
        int index = children.indexOf(action);
        removeChild(action);
        return index;
    }

    public void addChild(GraphElement child) {
        addChild(child, children.size());
    }

    public void addChild(GraphElement child, int index) {
        children.add(index, child);
        child.setParent(this);
        for (PropertyChangeListener delegatedListener : delegatedListeners) {
            child.setDelegatedListener(delegatedListener);
        }
        if (child.getId() == null && !Variable.class.equals(child.getClass())) {
            child.setId(getProcessDefinition().getNextNodeId());
        }
        firePropertyChange(NODE_ADDED, null, child);
        firePropertyChange(PROPERTY_CHILDREN_CHANGED, null, child);
    }

    public void swapChildren(GraphElement child1, GraphElement child2) {
        Collections.swap(children, children.indexOf(child1), children.indexOf(child2));
        firePropertyChange(PROPERTY_CHILDREN_CHANGED, null, children);
    }

    public void changeChildIndex(GraphElement child, GraphElement insertBefore) {
        if (insertBefore != null && child != null) {
            int old = children.indexOf(child);
            children.remove(child);
            int before = children.indexOf(insertBefore);
            children.add(before, child);
            firePropertyChange(PROPERTY_CHILDREN_CHANGED, old, before + 1);
        }
    }
    
    public <T extends GraphElement> List<T> getChildren(Class<T> type) {
        return getChildren(type, null);
    }

    public <T extends GraphElement> List<T> getChildren(Class<T> type, Predicate<T> predicate) {
        Stream<GraphElement> stream = children.stream().filter(e -> type.isAssignableFrom(e.getClass()));
        if (predicate != null) {
            stream = stream.filter(e -> predicate.apply((T) e));
        }
        return (List<T>) stream.collect(Collectors.toList());
    }

    public List<Node> getNodes() {
        return getChildren(Node.class);
    }

    public List<GraphElement> getElements() {
        return getChildren(GraphElement.class);
    }

    public <T extends GraphElement> List<T> getChildrenRecursive(Class<T> type) {
        List<T> items = new ArrayList<T>();
        for (GraphElement element : children) {
            if (type.isAssignableFrom(element.getClass())) {
                items.add((T) element);
            }
            items.addAll(element.getChildrenRecursive(type));
        }
        return items;
    }

    public <T extends GraphElement> T getFirstChild(Class<T> type) {
        for (GraphElement element : children) {
            if (type.isAssignableFrom(element.getClass())) {
                return (T) element;
            }
        }
        return null;
    }

    // Active implementation
    public void addAction(Action action, int index) {
        if (!(this instanceof ActionContainer)) {
            throw new IllegalStateException("It's not Active class ... " + this.getClass());
        }
        if (index == -1) {
            addChild(action);
        } else {
            addChild(action, index);
        }
    }

    public List<Action> getActions() {
        return getChildren(Action.class);
    }

    // Describable
    private String description;

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        String old = this.description;
        this.description = description;
        firePropertyChange(PROPERTY_DESCRIPTION, old, this.getDescription());
    }

    // Delegable
    private String delegationClassName;
    private String delegationConfiguration = "";

    public String getDelegationClassName() {
        return delegationClassName;
    }

    public void setDelegationClassName(String delegationClassName) {
        String old = getDelegationClassName();
        if (!Objects.equal(old, delegationClassName)) {
            this.delegationClassName = delegationClassName;
            firePropertyChange(PropertyNames.PROPERTY_CLASS, old, this.delegationClassName);
        }
    }

    public String getDelegationConfiguration() {
        return delegationConfiguration;
    }

    public void setDelegationConfiguration(String delegationConfiguration) {
        if (delegationConfiguration == null) {
            delegationConfiguration = "";
        }
        if (!this.delegationConfiguration.equals(delegationConfiguration)) {
            String old = this.delegationConfiguration;
            this.delegationConfiguration = delegationConfiguration;
            firePropertyChange(PROPERTY_CONFIGURATION, old, this.delegationConfiguration);
        }
    }

    @Override
    public List<Variable> getVariables(boolean expandComplexTypes, boolean includeSwimlanes, String... typeClassNameFilters) {
        return getProcessDefinition().getVariables(expandComplexTypes, includeSwimlanes, typeClassNameFilters);
    }

    public List<String> getVariableNames(boolean includeSwimlanes, String... typeClassNameFilters) {
        return VariableUtils.getVariableNames(getVariables(true, includeSwimlanes, typeClassNameFilters));
    }

    @Override
    public void firePropertyChange(String propName, Object old, Object newValue) {
        super.firePropertyChange(propName, old, newValue);
        if (!PROPERTY_DIRTY.equals(propName)) {
            if (!Objects.equal(old, newValue)) {
                setDirty();
            }
        }
    }

    @Override
    public Object getEditableValue() {
        return this;
    }

    @Override
    public boolean isPropertySet(Object id) {
        return false;
    }

    @Override
    public void resetPropertyValue(Object id) {
    }

    public boolean isDelegable() {
        return this instanceof Delegable;
    }

    @Override
    public final IPropertyDescriptor[] getPropertyDescriptors() {
        List<IPropertyDescriptor> descriptors = new ArrayList<IPropertyDescriptor>();
        descriptors.add(new PropertyDescriptor(PROPERTY_ID, Localization.getString("Node.property.id")));
        if (this instanceof NamedGraphElement && !(this instanceof TextAnnotation)) {
            if (((NamedGraphElement) this).canNameBeSetFromProperties()) {
                descriptors.add(new TextPropertyDescriptor(PROPERTY_NAME, Localization.getString("property.name")));
            } else {
                descriptors.add(new PropertyDescriptor(PROPERTY_NAME, Localization.getString("property.name")));
            }
        }
        if (this instanceof Describable) {
            descriptors
                    .add(new DescribablePropertyDescriptor(PROPERTY_DESCRIPTION, Localization.getString("property.description"), (Describable) this));
        }
        if (isDelegable()) {
            Delegable delegable = (Delegable) this;
            descriptors.add(new DelegableClassPropertyDescriptor(PROPERTY_CLASS, Localization.getString("property.delegation.class"), delegable));
            descriptors.add(new DelegableConfPropertyDescriptor(PROPERTY_CONFIGURATION, (Delegable) this,
                    Localization.getString("property.delegation.configuration")));
        }
        if (this instanceof ITimed && getProcessDefinition().getLanguage() == Language.JPDL) {
            Timer timer = ((ITimed) this).getTimer();
            if (timer != null) {
                descriptors.add(new DurationPropertyDescriptor(PROPERTY_TIMER_DELAY, timer.getProcessDefinition(), timer.getDelay(),
                        Localization.getString("property.duration")));
                descriptors.add(new TimerActionPropertyDescriptor(PROPERTY_TIMER_ACTION, Localization.getString("Timer.action"), timer));
            }
        }
        populateCustomPropertyDescriptors(descriptors);
        return descriptors.toArray(new IPropertyDescriptor[descriptors.size()]);
    }

    protected void populateCustomPropertyDescriptors(List<IPropertyDescriptor> descriptors) {
    }

    protected String safeStringValue(String canBeNull) {
        return canBeNull == null ? "" : canBeNull;
    }

    @Override
    public Object getPropertyValue(Object id) {
        if (PROPERTY_ID.equals(id)) {
            return getId();
        } else if (PROPERTY_CLASS.equals(id)) {
            return safeStringValue(getDelegationClassName());
        } else if (PROPERTY_CONFIGURATION.equals(id)) {
            return safeStringValue(getDelegationConfiguration());
        } else if (PROPERTY_DESCRIPTION.equals(id)) {
            return safeStringValue(getDescription());
        }
        return null;
    }

    @Override
    public void setPropertyValue(Object id, Object value) {
        if (PROPERTY_CLASS.equals(id)) {
            setDelegationClassName((String) value);
        } else if (PROPERTY_CONFIGURATION.equals(id)) {
            setDelegationConfiguration((String) value);
        } else if (PROPERTY_DESCRIPTION.equals(id)) {
            setDescription((String) value);
        }
    }

    @Override
    public String toString() {
        return getClass().getName() + " (" + getId() + ")";
    }

    public Image getEntryImage() {
        return getTypeDefinition().getImage(getProcessDefinition().getLanguage().getNotation());
    }

    public GraphElement makeCopy(GraphElement parent) {
        GraphElement copy = getTypeDefinition().createElement(parent, false);
        if (!Variable.class.equals(copy.getClass())) {
            copy.setId(parent.getProcessDefinition().getNextNodeId());
        }
        if (this instanceof Describable) {
            copy.setDescription(getDescription());
        }
        if (this instanceof ActionContainer) {
            List<? extends Action> actions = ((ActionContainer) this).getActions();
            for (Action action : actions) {
                action.makeCopy(copy);
            }
        }
        if (constraint != null) {
            copy.setConstraint(constraint.getCopy());
        }
        fillCopyCustomFields(copy);
        parent.addChild(copy);
        return copy;
    }

    protected void fillCopyCustomFields(GraphElement copy) {
    }

    public List<Variable> getUsedVariables(IFolder processFolder) {
        List<String> variableNames = Lists.newArrayList();
        if (this instanceof Delegable) {
            try {
                DelegableProvider provider = HandlerRegistry.getProvider(getDelegationClassName());
                variableNames.addAll(provider.getUsedVariableNames((Delegable) this));
            } catch (Exception e) {
                PluginLogger.logErrorWithoutDialog("Unable to get used variables in " + this, e);
            }
        }
        if (this instanceof ActionContainer) {
            List<? extends Action> actions = ((ActionContainer) this).getActions();
            for (Action action : actions) {
                try {
                    DelegableProvider provider = HandlerRegistry.getProvider(action.getDelegationClassName());
                    variableNames.addAll(provider.getUsedVariableNames(action));
                } catch (Exception e) {
                    PluginLogger.logErrorWithoutDialog("Unable to get used variables in " + action, e);
                }
            }
        }
        List<Variable> result = Lists.newArrayList();
        for (String variableName : variableNames) {
            Variable variable = VariableUtils.getVariableByName(getProcessDefinition(), variableName);
            if (variable != null) {
                result.add(variable);
            }
        }
        return result;
    }

    public String getLabel() {
        return id;
    }

}
