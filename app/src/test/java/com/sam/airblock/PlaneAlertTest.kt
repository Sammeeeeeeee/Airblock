package com.sam.airblock

import com.sam.airblock.data.PlaneAlertRepo
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaneAlertTest {

    @Test fun splitsPlainCsv() {
        assertEquals(
            listOf("AE01CE", "00-9001", "United States Air Force", "Boeing C-32A"),
            PlaneAlertRepo.splitCsv("AE01CE,00-9001,United States Air Force,Boeing C-32A"),
        )
    }

    @Test fun splitsQuotedFieldsWithCommas() {
        assertEquals(
            listOf("3C4444", "16+01", "Govt of Germany", "Airbus A350, ACJ", "A359"),
            PlaneAlertRepo.splitCsv("3C4444,16+01,Govt of Germany,\"Airbus A350, ACJ\",A359"),
        )
    }

    @Test fun splitsEscapedQuotes() {
        assertEquals(
            listOf("ABC123", "say \"hi\"", "x"),
            PlaneAlertRepo.splitCsv("ABC123,\"say \"\"hi\"\"\",x"),
        )
    }

    @Test fun keepsEmptyFields() {
        assertEquals(listOf("A", "", "C", ""), PlaneAlertRepo.splitCsv("A,,C,"))
    }
}
