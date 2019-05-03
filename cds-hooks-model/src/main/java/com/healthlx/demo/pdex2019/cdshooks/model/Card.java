package com.healthlx.demo.pdex2019.cdshooks.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.Setter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class Card {

  private String summary;
  private IndicatorEnum indicator;
  private Source source;
  private List<Suggestion> suggestions = new ArrayList<>();
  private List<Link> links = new ArrayList<>();

  /**
   * Add a suggestion.
   *
   * @param suggestionsItem The suggestion.
   * @return
   */
  public Card addSuggestionsItem(Suggestion suggestionsItem) {
    this.suggestions.add(suggestionsItem);
    return this;
  }

  /**
   * Add a link.
   *
   * @param linksItem The link.
   * @return
   */
  public Card addLinksItem(Link linksItem) {
    this.links.add(linksItem);
    return this;
  }

  public enum IndicatorEnum {
    INFO("info"),

    WARNING("warning"),

    HARD_STOP("hard-stop");

    private String value;

    IndicatorEnum(String value) {
      this.value = value;
    }

    /**
     * Create the enum value from a string. Needed because the values have illegal java chars.
     *
     * @param value One of the enum values.
     * @return indicatorEnum
     */
    @JsonCreator
    public static IndicatorEnum fromValue(String value) throws IOException {
      for (IndicatorEnum indicatorEnum : IndicatorEnum.values()) {
        if (indicatorEnum.toString().equals(value)) {
          return indicatorEnum;
        }
      }
      return null;
      //    throw new RuntimeException("Hook was set to '" + value +
      //        "', but it can only be one of: " + Arrays.toString(Hook.values()));
    }

    @JsonValue
    public String getValue() {
      return value;
    }

    @Override
    public String toString() {
      return String.valueOf(value);
    }
  }
}
