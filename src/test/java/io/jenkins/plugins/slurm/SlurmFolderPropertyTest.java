package io.jenkins.plugins.slurm;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import com.cloudbees.hudson.plugins.folder.Folder;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;

/**
 * Tests for {@link SlurmFolderProperty}.
 * Pattern inspired by KubernetesFolderPropertyTest.
 */
public class SlurmFolderPropertyTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    private SlurmCloud cloud1;
    private SlurmCloud cloud2;

    @Before
    public void setUp() throws Exception {
        // Create two clouds with usage restrictions
        cloud1 = SlurmTestUtil.createTestCloud("slurm1");
        cloud1.setUsageRestricted(true);
        r.jenkins.clouds.add(cloud1);

        cloud2 = SlurmTestUtil.createTestCloud("slurm2");
        cloud2.setUsageRestricted(true);
        r.jenkins.clouds.add(cloud2);
    }

    @Test
    public void testPropertySavedOnFirstSave() throws Exception {
        Folder folder = r.jenkins.createProject(Folder.class, "folder001");
        SlurmFolderProperty prop = new SlurmFolderProperty();
        folder.addProperty(prop);

        Folder after = r.configRoundtrip(folder);
        
        // Property should exist after saving
        assertThat(
            "Property exists after saving",
            after.getProperties().get(SlurmFolderProperty.class),
            notNullValue()
        );
        
        // No clouds selected initially
        assertThat(
            "No selected clouds",
            after.getProperties().get(SlurmFolderProperty.class).getPermittedClouds(),
            empty()
        );

        // Add one cloud
        folder.getProperties()
            .get(SlurmFolderProperty.class)
            .setPermittedClouds(Collections.singletonList("slurm1"));
        
        after = r.configRoundtrip(folder);
        
        assertThat(
            "slurm1 cloud is added",
            after.getProperties().get(SlurmFolderProperty.class).getPermittedClouds(),
            contains("slurm1")
        );
    }

    @Test
    public void testNestedFolderInheritance() throws Exception {
        // Create parent folder with slurm1 permission
        Folder folder = r.jenkins.createProject(Folder.class, "parent");
        SlurmFolderProperty prop = new SlurmFolderProperty();
        prop.setPermittedClouds(Collections.singletonList("slurm1"));
        folder.addProperty(prop);

        // Create subfolder with slurm2 permission
        Folder subFolder = folder.createProject(Folder.class, "subfolder001");
        SlurmFolderProperty prop2 = new SlurmFolderProperty();
        prop2.setPermittedClouds(Collections.singletonList("slurm2"));
        subFolder.addProperty(prop2);

        Folder after = r.configRoundtrip(subFolder);
        
        // Subfolder should have both inherited and own permissions
        assertThat(
            "Contains own and inherited cloud",
            after.getProperties().get(SlurmFolderProperty.class).getPermittedClouds(),
            containsInAnyOrder("slurm1", "slurm2")
        );
    }

    @Test
    public void testMultipleCloudsSelection() throws Exception {
        Folder folder = r.jenkins.createProject(Folder.class, "folder002");
        SlurmFolderProperty prop = new SlurmFolderProperty();
        prop.setPermittedClouds(java.util.Arrays.asList("slurm1", "slurm2"));
        folder.addProperty(prop);

        Folder after = r.configRoundtrip(folder);
        
        assertThat(
            "Both clouds are permitted",
            after.getProperties().get(SlurmFolderProperty.class).getPermittedClouds(),
            containsInAnyOrder("slurm1", "slurm2")
        );
    }

    @Test
    public void testEmptyPermittedClouds() throws Exception {
        Folder folder = r.jenkins.createProject(Folder.class, "folder003");
        SlurmFolderProperty prop = new SlurmFolderProperty();
        // Don't set any permitted clouds
        folder.addProperty(prop);

        Folder after = r.configRoundtrip(folder);
        
        assertThat(
            "No clouds permitted",
            after.getProperties().get(SlurmFolderProperty.class).getPermittedClouds(),
            empty()
        );
    }

    @Test
    public void testNullPermittedClouds() throws Exception {
        Folder folder = r.jenkins.createProject(Folder.class, "folder004");
        SlurmFolderProperty prop = new SlurmFolderProperty();
        prop.setPermittedClouds(null);
        folder.addProperty(prop);

        Folder after = r.configRoundtrip(folder);
        
        // Should handle null gracefully
        assertNotNull(after.getProperties().get(SlurmFolderProperty.class));
        assertThat(
            "Null handled as empty",
            after.getProperties().get(SlurmFolderProperty.class).getPermittedClouds(),
            empty()
        );
    }

    @Test
    public void testDeepNesting() throws Exception {
        // Create a deep folder hierarchy
        Folder level1 = r.jenkins.createProject(Folder.class, "level1");
        SlurmFolderProperty prop1 = new SlurmFolderProperty();
        prop1.setPermittedClouds(Collections.singletonList("slurm1"));
        level1.addProperty(prop1);

        Folder level2 = level1.createProject(Folder.class, "level2");
        // Level 2 has no property set

        Folder level3 = level2.createProject(Folder.class, "level3");
        SlurmFolderProperty prop3 = new SlurmFolderProperty();
        prop3.setPermittedClouds(Collections.singletonList("slurm2"));
        level3.addProperty(prop3);

        Folder after = r.configRoundtrip(level3);
        
        // Level 3 should inherit from level 1 and have its own from level 3
        assertThat(
            "Inherits through multiple levels",
            after.getProperties().get(SlurmFolderProperty.class).getPermittedClouds(),
            containsInAnyOrder("slurm1", "slurm2")
        );
    }

    @Test
    public void testPropertyDescriptor() {
        SlurmFolderProperty.DescriptorImpl descriptor = new SlurmFolderProperty.DescriptorImpl();
        
        assertNotNull(descriptor);
        assertEquals("Slurm Cloud Restrictions", descriptor.getDisplayName());
    }

    @Test
    public void testGetPermittedClouds() throws Exception {
        Folder folder = r.jenkins.createProject(Folder.class, "folder005");
        SlurmFolderProperty prop = new SlurmFolderProperty();
        prop.setPermittedClouds(java.util.Arrays.asList("slurm1", "slurm2"));
        folder.addProperty(prop);

        java.util.Collection<String> permitted = prop.getPermittedClouds();
        
        assertNotNull(permitted);
        assertEquals(2, permitted.size());
        assertTrue(permitted.contains("slurm1"));
        assertTrue(permitted.contains("slurm2"));
    }

    @Test
    public void testSetPermittedClouds() throws Exception {
        Folder folder = r.jenkins.createProject(Folder.class, "folder006");
        SlurmFolderProperty prop = new SlurmFolderProperty();
        folder.addProperty(prop);

        // Initially empty
        assertThat(prop.getPermittedClouds(), empty());

        // Set clouds
        prop.setPermittedClouds(java.util.Arrays.asList("slurm1", "slurm2"));
        
        assertThat(prop.getPermittedClouds(), containsInAnyOrder("slurm1", "slurm2"));
    }

    @Test
    public void testPermissionCheckWithRestrictedCloud() throws Exception {
        Folder folder = r.jenkins.createProject(Folder.class, "folder007");
        SlurmFolderProperty prop = new SlurmFolderProperty();
        prop.setPermittedClouds(Collections.singletonList("slurm1"));
        folder.addProperty(prop);

        // Cloud1 is restricted and folder permits it
        assertTrue(cloud1.isUsageRestricted());
        
        // Verify folder has permission
        assertTrue(prop.getPermittedClouds().contains("slurm1"));
        assertFalse(prop.getPermittedClouds().contains("slurm2"));
    }

    @Test
    public void testNoPropertyMeansNoPermissions() throws Exception {
        // Folder without SlurmFolderProperty
        Folder folder = r.jenkins.createProject(Folder.class, "folder008");
        
        // No property means no specific permissions
        assertNull(folder.getProperties().get(SlurmFolderProperty.class));
    }

    @Test
    public void testAddingCloudAfterProperty() throws Exception {
        Folder folder = r.jenkins.createProject(Folder.class, "folder009");
        SlurmFolderProperty prop = new SlurmFolderProperty();
        prop.setPermittedClouds(java.util.Arrays.asList("slurm1", "slurm3"));
        folder.addProperty(prop);

        // Cloud3 doesn't exist yet
        assertNull(r.jenkins.clouds.getByName("slurm3"));

        // Add cloud3 later
        SlurmCloud cloud3 = SlurmTestUtil.createTestCloud("slurm3");
        cloud3.setUsageRestricted(true);
        r.jenkins.clouds.add(cloud3);

        // Property should still have permission for cloud3
        assertTrue(prop.getPermittedClouds().contains("slurm3"));
    }

    @Test
    public void testRemovingPermittedCloud() throws Exception {
        Folder folder = r.jenkins.createProject(Folder.class, "folder010");
        SlurmFolderProperty prop = new SlurmFolderProperty();
        prop.setPermittedClouds(java.util.Arrays.asList("slurm1", "slurm2"));
        folder.addProperty(prop);

        // Verify both clouds are permitted
        assertThat(prop.getPermittedClouds(), containsInAnyOrder("slurm1", "slurm2"));

        // Update to remove slurm2
        prop.setPermittedClouds(Collections.singletonList("slurm1"));

        assertThat(prop.getPermittedClouds(), contains("slurm1"));
        assertThat(prop.getPermittedClouds(), not(contains("slurm2")));
    }

    @Test
    public void testDuplicateCloudNames() throws Exception {
        Folder folder = r.jenkins.createProject(Folder.class, "folder011");
        SlurmFolderProperty prop = new SlurmFolderProperty();
        // Try to add same cloud twice
        prop.setPermittedClouds(java.util.Arrays.asList("slurm1", "slurm1"));
        folder.addProperty(prop);

        // Should handle duplicates (implementation dependent)
        assertNotNull(prop.getPermittedClouds());
        assertTrue(prop.getPermittedClouds().contains("slurm1"));
    }
}

