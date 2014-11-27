/**
 * (c) 2003-2014 MuleSoft, Inc. The software in this package is published under the terms of the CPAL v1.0 license,
 * a copy of which has been included with this distribution in the LICENSE.md file.
 */

package org.mule.module.cmis.automation.testcases;

import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mule.module.cmis.automation.CMISTestParent;
import org.mule.module.cmis.automation.RegressionTests;
import org.mule.module.cmis.automation.SmokeTests;
import org.mule.modules.tests.ConnectorTestUtils;

import static org.junit.Assert.*;

public class CheckInTestCases extends CMISTestParent {

    private String folderId;


    @Before
    public void setUp() throws Exception {
        initializeTestRunMessage("checkInTestData");

        upsertOnTestRunMessage("parentObjectId", getRootFolderId());
        folderId = createFolderAndUpsertFolderIdOnTestRunMessage();
        String objectId = ((ObjectId) runFlowAndGetPayload("create-document-by-id")).getId();
        String checkOutId = checkOut(objectId).getId();
        upsertOnTestRunMessage("documentId", checkOutId);
        // Wait for Alfresco to index the checked out documents
        Thread.sleep(GET_CHECKOUT_DOCS_DELAY);
    }

    @Category({SmokeTests.class, RegressionTests.class})
    @Test
    public void testCheckIn() {
        boolean found = false;
        try {
            ObjectId checkedInId = runFlowAndGetPayload("check-in");
            assertTrue(checkedInId != null && !checkedInId.getId().isEmpty() && !checkedInId.getId().trim().isEmpty());
            // Wait for Alfresco to index the checked in documents
            Thread.sleep(GET_CHECKOUT_DOCS_DELAY);

            ItemIterable<Document> checkedOutDocuments = runFlowAndGetPayload("get-checkout-docs");
            for (Document doc : checkedOutDocuments) {
                try {
                    if (doc != null && doc.isPrivateWorkingCopy()) {
                        found = true;
                    }
                } catch (NullPointerException npe) {
                    // If no checked out document found instead of false, isPrivateWorkingCopy throws a NullPointerException
                }
            }
            assertFalse(found);

        } catch (Exception e) {
            fail(ConnectorTestUtils.getStackTrace(e));
        }
    }

    @After
    public void tearDown() throws Exception {
        deleteTree(folderId, true, true);
    }

}
