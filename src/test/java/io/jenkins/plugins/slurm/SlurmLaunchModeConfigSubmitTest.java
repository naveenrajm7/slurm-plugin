package io.jenkins.plugins.slurm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import org.htmlunit.FailingHttpStatusCodeException;
import org.htmlunit.html.DomNode;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlRadioButtonInput;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Submits the real cloud and template configuration forms. These tests do not stub Stapler
 * binding, so they fail when {@code f:radioBlock} posts {@code launchMode} as an object.
 */
@WithJenkins
class SlurmLaunchModeConfigSubmitTest {

    @Test
    void configureCloud_savesEachLaunchMode(JenkinsRule j) throws Exception {
        assertCloudMode(j, "native-cloud", "NATIVE", "javaPath", "/opt/java/bin/java");
        assertCloudMode(j, "pyxis-cloud", "PYXIS", "containerImage", "/opt/images/jenkins-agent.sqsh");
        assertCloudMode(j, "vmocs-cloud", "VMOCS", "vmImage", "base-ubuntu");
    }

    @Test
    void newCloudPage_savesSelectedLaunchMode(JenkinsRule j) throws Exception {
        JenkinsRule.WebClient webClient = j.createWebClient();
        HtmlPage typePage = webClient.goTo("cloud/new");
        HtmlForm typeForm = typePage.getFormByName("createItem");
        typeForm.getInputByName("name").setValue("created-slurm");
        selectRadio(typeForm, "mode", new SlurmCloud.DescriptorImpl().getId());
        enable(typePage.getHtmlElementById("ok"));

        HtmlPage configPage = submit(j, typeForm);
        HtmlForm config = configPage.getFormByName("config");
        HtmlInput name = config.getInputByName("_.name");
        if (name.getValue().isBlank()) {
            name.setValue("created-slurm");
        }
        setNestedField(selectRadio(config, "launchMode", "NATIVE"), "javaPath", "/opt/created/java");
        submit(j, config);

        SlurmCloud cloud = (SlurmCloud) j.jenkins.getCloud("created-slurm");
        assertNotNull(cloud, "new cloud page did not store the cloud");
        assertSelectedCloud(cloud, "NATIVE", "/opt/created/java");
    }

    @Test
    void configureCloud_preservesJobTemplates(JenkinsRule j) throws Exception {
        SlurmCloud existing = cloud("with-templates");
        SlurmJobTemplate gpu = new SlurmJobTemplate("gpu", "gpu");
        SlurmJobTemplate cpu = new SlurmJobTemplate("cpu", "linux");
        existing.setJobTemplates(new ArrayList<>(List.of(gpu, cpu)));
        j.jenkins.clouds.add(existing);

        HtmlPage page = j.createWebClient().goTo("cloud/with-templates/configure");
        submit(j, page.getFormByName("config"));

        SlurmCloud saved = (SlurmCloud) j.jenkins.getCloud("with-templates");
        assertNotNull(saved);
        assertNotSame(existing, saved);
        assertEquals(
                List.of("gpu", "cpu"),
                saved.getJobTemplates().stream().map(SlurmJobTemplate::getName).toList());
        assertEquals("NATIVE", saved.getLaunchMode());
    }

    @Test
    void configureTemplate_savesEachLaunchMode(JenkinsRule j) throws Exception {
        SlurmCloud owner = cloud("template-cloud");
        j.jenkins.clouds.add(owner);

        SlurmJobTemplate inherit = template(owner, "inherit-me");
        AgentLaunchConfig previous = new AgentLaunchConfig();
        previous.setJavaPath("/opt/old/java");
        previous.setJarPath("/opt/old/agent.jar");
        inherit.setLaunchMode("NATIVE");
        inherit.setAgent(previous);
        submitTemplate(j, owner.name, inherit, "INHERIT", null, null);
        assertFalse(inherit.isExplicitLaunchMode());
        assertNull(inherit.getAgent());
        assertNull(inherit.getPyxis());
        assertNull(inherit.getVmocs());

        SlurmJobTemplate nativeTemplate = template(owner, "native-me");
        submitTemplate(j, owner.name, nativeTemplate, "NATIVE", "javaPath", "/opt/template/java");
        assertEquals("NATIVE", nativeTemplate.getLaunchMode());
        assertTrue(nativeTemplate.isExplicitLaunchMode());
        assertNotNull(nativeTemplate.getAgent());
        assertEquals("/opt/template/java", nativeTemplate.getAgent().getJavaPath());
        assertNull(nativeTemplate.getPyxis());
        assertNull(nativeTemplate.getVmocs());

        SlurmJobTemplate pyxisTemplate = template(owner, "pyxis-me");
        submitTemplate(j, owner.name, pyxisTemplate, "PYXIS", "containerImage", "/opt/images/template.sqsh");
        assertEquals("PYXIS", pyxisTemplate.getLaunchMode());
        assertNotNull(pyxisTemplate.getPyxis());
        assertEquals("/opt/images/template.sqsh", pyxisTemplate.getPyxis().getContainerImage());
        assertNull(pyxisTemplate.getAgent());
        assertNull(pyxisTemplate.getVmocs());

        SlurmJobTemplate vmocsTemplate = template(owner, "vmocs-me");
        submitTemplate(j, owner.name, vmocsTemplate, "VMOCS", "vmImage", "guest-ubuntu");
        assertEquals("VMOCS", vmocsTemplate.getLaunchMode());
        assertNotNull(vmocsTemplate.getVmocs());
        assertEquals("guest-ubuntu", vmocsTemplate.getVmocs().getVmImage());
        assertNull(vmocsTemplate.getAgent());
        assertNull(vmocsTemplate.getPyxis());
    }

    @Test
    void newTemplatePage_savesEachLaunchMode(JenkinsRule j) throws Throwable {
        SlurmCloud owner = cloud("new-template-cloud");
        j.jenkins.clouds.add(owner);

        createTemplate(j, owner.name, "inherit-new", "INHERIT", null, null);
        createTemplate(j, owner.name, "native-new", "NATIVE", "javaPath", "/opt/new/java");
        createTemplate(j, owner.name, "pyxis-new", "PYXIS", "containerImage", "/opt/images/new.sqsh");
        createTemplate(j, owner.name, "vmocs-new", "VMOCS", "vmImage", "new-ubuntu");
        assertCreatedTemplates(owner);

        j.restart();

        SlurmCloud reloaded = (SlurmCloud) j.jenkins.getCloud(owner.name);
        assertNotNull(reloaded);
        assertCreatedTemplates(reloaded);
    }

    private static void assertCloudMode(JenkinsRule j, String name, String mode, String field, String value)
            throws Exception {
        SlurmCloud existing = cloud(name);
        j.jenkins.clouds.add(existing);

        HtmlPage page = j.createWebClient().goTo("cloud/" + name + "/configure");
        HtmlForm form = page.getFormByName("config");
        setNestedField(selectRadio(form, "launchMode", mode), field, value);
        submit(j, form);

        SlurmCloud saved = (SlurmCloud) j.jenkins.getCloud(name);
        assertNotNull(saved);
        assertNotSame(existing, saved, mode);
        assertEquals(mode, saved.getLaunchMode(), mode);
        assertTrue(saved.isExplicitLaunchMode(), mode);
        switch (mode) {
            case "NATIVE" -> {
                assertNotNull(saved.getAgent(), mode);
                assertEquals(value, saved.getAgent().getJavaPath(), mode);
                assertNull(saved.getPyxis(), mode);
                assertNull(saved.getVmocs(), mode);
            }
            case "PYXIS" -> {
                assertNotNull(saved.getPyxis(), mode);
                assertEquals(value, saved.getPyxis().getContainerImage(), mode);
                assertNull(saved.getAgent(), mode);
                assertNull(saved.getVmocs(), mode);
            }
            case "VMOCS" -> {
                assertNotNull(saved.getVmocs(), mode);
                assertEquals(value, saved.getVmocs().getVmImage(), mode);
                assertNull(saved.getAgent(), mode);
                assertNull(saved.getPyxis(), mode);
            }
            default -> fail(mode);
        }
    }

    private static void assertSelectedCloud(SlurmCloud cloud, String mode, String javaPath) {
        assertEquals(mode, cloud.getLaunchMode());
        assertTrue(cloud.isExplicitLaunchMode());
        assertNotNull(cloud.getAgent());
        assertEquals(javaPath, cloud.getAgent().getJavaPath());
        assertNull(cloud.getPyxis());
        assertNull(cloud.getVmocs());
    }

    private static void assertCreatedTemplates(SlurmCloud owner) {
        SlurmJobTemplate inherit = owner.getTemplate("inherit-new");
        assertNotNull(inherit);
        assertFalse(inherit.isExplicitLaunchMode());
        assertNull(inherit.getAgent());
        assertNull(inherit.getPyxis());
        assertNull(inherit.getVmocs());

        SlurmJobTemplate nativeTemplate = owner.getTemplate("native-new");
        assertNotNull(nativeTemplate);
        assertEquals("NATIVE", nativeTemplate.getLaunchMode());
        assertTrue(nativeTemplate.isExplicitLaunchMode());
        assertNotNull(nativeTemplate.getAgent());
        assertEquals("/opt/new/java", nativeTemplate.getAgent().getJavaPath());
        assertNull(nativeTemplate.getPyxis());
        assertNull(nativeTemplate.getVmocs());

        SlurmJobTemplate pyxis = owner.getTemplate("pyxis-new");
        assertNotNull(pyxis);
        assertEquals("PYXIS", pyxis.getLaunchMode());
        assertNotNull(pyxis.getPyxis());
        assertEquals("/opt/images/new.sqsh", pyxis.getPyxis().getContainerImage());
        assertNull(pyxis.getAgent());
        assertNull(pyxis.getVmocs());

        SlurmJobTemplate vmocs = owner.getTemplate("vmocs-new");
        assertNotNull(vmocs);
        assertEquals("VMOCS", vmocs.getLaunchMode());
        assertNotNull(vmocs.getVmocs());
        assertEquals("new-ubuntu", vmocs.getVmocs().getVmImage());
        assertNull(vmocs.getAgent());
        assertNull(vmocs.getPyxis());
    }

    /**
     * Posts the new-template form. The page is {@code /cloud/<name>/new}; its form submits to
     * {@code /cloud/<name>/create}.
     */
    private static void createTemplate(
            JenkinsRule j, String cloudName, String templateName, String mode, String field, String value)
            throws Exception {
        HtmlPage page = j.createWebClient().goTo("cloud/" + cloudName + "/new");
        HtmlForm form = page.getFormByName("config");
        form.getInputByName("_.name").setValue(templateName);
        fillEmptyNumbers(form);
        HtmlRadioButtonInput radio = selectRadio(form, "launchMode", mode);
        if (field != null) {
            setNestedField(radio, field, value);
        }
        submit(j, form);
    }

    private static void submitTemplate(
            JenkinsRule j, String cloudName, SlurmJobTemplate template, String mode, String field, String value)
            throws Exception {
        HtmlPage page = j.createWebClient().goTo("cloud/" + cloudName + "/template/" + template.getName() + "/");
        HtmlForm form = page.getFormByName("config");
        fillEmptyNumbers(form);
        HtmlRadioButtonInput radio = selectRadio(form, "launchMode", mode);
        if (field != null) {
            setNestedField(radio, field, value);
        }
        submit(j, form);
    }

    private static SlurmCloud cloud(String name) {
        return new SlurmCloud(name, "http://slurm-controller:6820", "", "compute", 10, 60);
    }

    private static SlurmJobTemplate template(SlurmCloud owner, String name) {
        SlurmJobTemplate template = new SlurmJobTemplate(name, "linux");
        template.setTasksPerNode(1);
        template.setNtasksPerTres(1);
        List<SlurmJobTemplate> templates = new ArrayList<>(owner.getJobTemplates());
        templates.add(template);
        owner.setJobTemplates(templates);
        return template;
    }

    private static HtmlRadioButtonInput selectRadio(HtmlForm form, String name, String value) {
        for (HtmlRadioButtonInput radio : form.getRadioButtonsByName(name)) {
            if (value.equals(radio.getValueAttribute())) {
                radio.setChecked(true);
                return radio;
            }
        }
        throw new AssertionError("No " + name + " radio with value " + value);
    }

    private static void setNestedField(HtmlRadioButtonInput radio, String field, String value) {
        HtmlElement container = radioBlock(radio);
        HtmlElement match = container.querySelector("input[name$='" + field + "']");
        if (!(match instanceof HtmlInput input)) {
            throw new AssertionError(
                    "No input ending with " + field + " in the " + radio.getValueAttribute() + " block");
        }
        input.setValue(value);
    }

    private static HtmlElement radioBlock(HtmlRadioButtonInput radio) {
        for (DomNode node = radio; node != null; node = node.getParentNode()) {
            if (node instanceof HtmlElement element
                    && element.getAttribute("class").contains("radioBlock-container")) {
                return element;
            }
        }
        throw new AssertionError("radioBlock-container not found for " + radio.getValueAttribute());
    }

    private static void fillEmptyNumbers(HtmlForm form) {
        for (DomNode node : form.getElementsByTagName("input")) {
            if (node instanceof HtmlInput input
                    && "number".equals(input.getAttribute("type"))
                    && input.getValue().isBlank()) {
                input.setValue("1");
            }
        }
    }

    private static void enable(HtmlElement element) {
        element.removeAttribute("disabled");
    }

    private static HtmlPage submit(JenkinsRule j, HtmlForm form) throws Exception {
        try {
            HtmlPage page = j.submit(form);
            String body = page.getWebResponse().getContentAsString();
            if (body.contains("NoStaplerConstructorException") || body.contains("Failed to instantiate")) {
                fail(abbreviate(body));
            }
            return page;
        } catch (FailingHttpStatusCodeException e) {
            String body =
                    e.getResponse() == null ? e.getMessage() : e.getResponse().getContentAsString();
            fail(e.getStatusCode() + " " + abbreviate(body));
            throw e;
        }
    }

    private static String abbreviate(String body) {
        String flattened = body.replaceAll("\\s+", " ");
        return flattened.substring(0, Math.min(flattened.length(), 1500));
    }
}
