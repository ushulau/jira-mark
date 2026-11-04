package dev.vspex.jira.mark.remote;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AliasGeneratorTest {
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "Change Urgency|changeUrgency|changeUrgency",
        "Comments-Change Urgency|changeUrgencyComments|changeUrgencyComments",
        "Comments - Complexity|complexityComments|complexityComments",
        "Is this a Temp Change?|isThisATempChange|tempChange",
        "Planned Start Date Time|plannedStartDateTime|plannedStartDateTime",
        "Change Type (Primary Domain)|changeTypePrimaryDomain|changeTypePrimaryDomain",
        "Approver(s)|approvers|approvers",
        "R&D Owner|rAndDOwner|rAndDOwner",
        "Has the Rules Matrix been updated to reflect the Rule changes?|hasTheRulesMatrixBeenUpdatedToReflectTheRuleChanges|rulesMatrixUpdatedReflectRuleChanges",
        "Reg SCI Classification - Materiality of the Change|regSciClassificationMaterialityOfTheChange|regSciClassificationMaterialityChange",
        "2nd Approver|f2ndApprover|f2ndApprover",
      })
  void examples(String name, String full, String shortAlias) {
    assertEquals(full, AliasGenerator.baseAlias(name, "full", Set.of()));
    assertEquals(shortAlias, AliasGenerator.baseAlias(name, "short", Set.of()));
  }

  @Test
  void dropWords() {
    assertEquals("urgency", AliasGenerator.baseAlias("Change Urgency", "short", Set.of("change")));
    assertEquals(
        "urgencyComments",
        AliasGenerator.baseAlias("Comments-Change Urgency", "short", Set.of("change")));
  }

  @Test
  void collisionsApplyToEveryMemberRegardlessOfOrder() {
    var names = new LinkedHashMap<String, String>();
    names.put("customfield_10301", "Risk Rating");
    names.put("customfield_10302", "Risk Rating");
    var forward = AliasGenerator.generate(names, Map.of(), "short", Set.of());
    assertEquals("riskRating10301", forward.get("customfield_10301"));
    assertEquals("riskRating10302", forward.get("customfield_10302"));
    var reversed = new LinkedHashMap<String, String>();
    reversed.put("customfield_10302", "Risk Rating");
    reversed.put("customfield_10301", "Risk Rating");
    assertEquals(forward, AliasGenerator.generate(reversed, Map.of(), "short", Set.of()));
  }

  @Test
  void standardIdCollisionSuffixesCustomField() {
    var names = new LinkedHashMap<String, String>();
    names.put("description", "Description");
    names.put("customfield_10500", "Description");
    var aliases = AliasGenerator.generate(names, Map.of(), "short", Set.of());
    assertEquals("description", aliases.get("description"));
    assertEquals("description10500", aliases.get("customfield_10500"));
  }

  @Test
  void stabilityKeepsExistingAliases() {
    var names = new LinkedHashMap<String, String>();
    names.put("customfield_10302", "Change Urgency");
    names.put("customfield_10303", "Brand New Field");
    var existing = Map.of("customfield_10302", "urgency");
    var aliases = AliasGenerator.generate(names, existing, "short", Set.of("change"));
    assertEquals("urgency", aliases.get("customfield_10302"));
    assertEquals("brandNewField", aliases.get("customfield_10303"));
  }

  @Test
  void longAliasesAreKeptWithReviewNotes() {
    var aliases = Map.of("customfield_1", "hasTheRulesMatrixBeenUpdatedToReflectTheRuleChanges");
    var notes = AliasGenerator.longAliasNotes(aliases, 40);
    assertEquals(1, notes.size());
    assertTrue(notes.get(0).contains("customfield_1"));
    assertTrue(AliasGenerator.longAliasNotes(Map.of("a", "short"), 40).isEmpty());
  }
}
