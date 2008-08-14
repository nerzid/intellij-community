package com.intellij.lexer;

import com.intellij.codeInsight.completion.CompletionUtil;
import com.intellij.psi.impl.source.tree.TreeUtil;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.util.text.CharArrayUtil;
import org.jetbrains.annotations.NonNls;

import java.util.HashMap;

/**
 * Created by IntelliJ IDEA.
 * User: Maxim.Mossienko
 * Date: Oct 7, 2004
 * Time: 2:29:06 PM
 * To change this template use File | Settings | File Templates.
 */
abstract class BaseHtmlLexer extends LexerBase {
  protected Lexer baseLexer;
  protected static final int BASE_STATE_MASK = 0x3F;
  private static final int SEEN_STYLE = 0x40;
  private static final int SEEN_TAG = 0x80;
  private static final int SEEN_SCRIPT = 0x100;
  private static final int SEEN_ATTRIBUTE = 0x200;
  private static final int SEEN_CONTENT_TYPE = 0x400;
  protected static final int BASE_STATE_SHIFT = 11;

  private boolean seenTag;
  private boolean seenAttribute;
  private boolean seenStyle;
  private boolean seenScript;
  private boolean caseInsensitive;
  private boolean seenContentType;

  static final TokenSet TOKENS_TO_MERGE = TokenSet.create(XmlTokenType.XML_COMMENT_CHARACTERS, XmlTokenType.XML_WHITE_SPACE, XmlTokenType.XML_REAL_WHITE_SPACE,
                                                          XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN, XmlTokenType.XML_DATA_CHARACTERS,
                                                          XmlTokenType.XML_TAG_CHARACTERS);

  public interface TokenHandler {
    void handleElement(Lexer lexer);
  }

  public class XmlNameHandler implements TokenHandler {
    @NonNls private static final String TOKEN_SCRIPT = "script";
    @NonNls private static final String TOKEN_STYLE = "style";
    @NonNls private static final String TOKEN_ON = "on";

    public void handleElement(Lexer lexer) {
      final CharSequence buffer = lexer.getBufferSequence();
      final char firstCh = buffer.charAt(lexer.getTokenStart());
      // support for style in any attribute that ends with style
      //final int i = lexer.getTokenEnd() - "style".length();
      //final char ch = i > lexer.getTokenStart() ? buffer[i]:firstCh;

      if (seenScript && !seenTag) {
        seenContentType = false;

        if (((firstCh == 'l' || firstCh == 't') || (caseInsensitive && (firstCh == 'L' || firstCh == 'T')))) {
          @NonNls String name = TreeUtil.getTokenText(lexer);
          if (caseInsensitive) name = name.toLowerCase();

          if ("language".equals(name) || "type".equals(name)) {
            seenContentType = true;
          }
        }

        return;
      }

      if ( /*ch !='s' &&*/
          firstCh !='o' && firstCh !='s' &&
          (!caseInsensitive || (/*ch !='S' &&*/ firstCh !='S' && firstCh !='O') )
          ) {
        return; // optimization
      }

      String name = TreeUtil.getTokenText(lexer);
      if (caseInsensitive) name = name.toLowerCase();

      boolean style = name.equals(TOKEN_STYLE); //name.endsWith("style");
      boolean script = name.equals(TOKEN_SCRIPT) || 
                       (name.startsWith(TOKEN_ON) && name.indexOf(':') == -1);

      if (style || script) {
        // encountered tag name in end of tag
        if (seenTag) {
          seenTag = false;
          return;
        }

        seenStyle = style;
        seenScript = script;

        int state = getState() & BASE_STATE_MASK;

        if (!isHtmlTagState(state)) {
          seenAttribute=true;
        }
      }
    }
  }

  class XmlAttributeValueEndHandler implements TokenHandler {
    public void handleElement(Lexer lexer) {
      if (seenAttribute) {
        seenStyle = false;
        seenScript = false;
        seenAttribute = false;
      }
      seenContentType = false;
    }
  }

  class XmlAttributeValueHandler implements TokenHandler {
    public void handleElement(Lexer lexer) {
      if (seenContentType) {
        assert seenScript && !seenAttribute;

        @NonNls String name = TreeUtil.getTokenText(lexer);
        if (caseInsensitive) name = name.toLowerCase();
        if (name.indexOf("javascript") == -1 && name.indexOf("jscript") == -1) {
          seenScript = false;
          seenTag = true;    // will be switched of on tag name in end
        }
      }
    }
  }

  class XmlTagClosedHandler implements TokenHandler {
    public void handleElement(Lexer lexer) {
      if (seenAttribute) {
        seenScript=false;
        seenStyle=false;

        seenAttribute=false;
      } else {
        if (seenStyle || seenScript) {
          seenTag=true;
        }
      }
    }
  }

  class XmlTagEndHandler implements TokenHandler {
    public void handleElement(Lexer lexer) {
      seenStyle=false;
      seenScript=false;
      seenAttribute=false;
      seenContentType=false;
    }
  }

  private HashMap<IElementType,TokenHandler> tokenHandlers = new HashMap<IElementType, TokenHandler>();

  protected BaseHtmlLexer(Lexer _baseLexer, boolean _caseInsensitive)  {
    baseLexer = _baseLexer;
    caseInsensitive = _caseInsensitive;

    XmlNameHandler value = new XmlNameHandler();
    tokenHandlers.put(XmlTokenType.XML_NAME,value);
    tokenHandlers.put(XmlTokenType.XML_TAG_NAME,value);
    tokenHandlers.put(XmlTokenType.XML_TAG_END,new XmlTagClosedHandler());
    tokenHandlers.put(XmlTokenType.XML_END_TAG_START,new XmlTagEndHandler());
    tokenHandlers.put(XmlTokenType.XML_EMPTY_ELEMENT_END,new XmlTagEndHandler());
    tokenHandlers.put(XmlTokenType.XML_ATTRIBUTE_VALUE_END_DELIMITER,new XmlAttributeValueEndHandler());
    tokenHandlers.put(XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN,new XmlAttributeValueHandler());
  }

  protected void registerHandler(IElementType elementType, TokenHandler value) {
    final TokenHandler tokenHandler = tokenHandlers.get(elementType);

    if (tokenHandler != null) {
      final TokenHandler newHandler = value;
      value = new TokenHandler() {
        public void handleElement(final Lexer lexer) {
          tokenHandler.handleElement(lexer);
          newHandler.handleElement(lexer);
        }
      };
    }

    tokenHandlers.put(elementType,value);
  }

  public void start(char[] buffer, int startOffset, int endOffset, int initialState) {
    initState(initialState);

    baseLexer.start(buffer, startOffset, endOffset, initialState & BASE_STATE_MASK);
  }

  public void start(final CharSequence buffer, final int startOffset, final int endOffset, final int initialState) {
    initState(initialState);
    baseLexer.start(buffer, startOffset, endOffset, initialState & BASE_STATE_MASK);
  }

  private void initState(final int initialState) {
    seenScript = (initialState & SEEN_SCRIPT)!=0;
    seenStyle = (initialState & SEEN_STYLE)!=0;
    seenTag = (initialState & SEEN_TAG)!=0;
    seenAttribute = (initialState & SEEN_ATTRIBUTE)!=0;
    seenContentType = (initialState & SEEN_CONTENT_TYPE) != 0;
  }

  protected int skipToTheEndOfTheEmbeddment() {
    int myTokenEnd = baseLexer.getTokenEnd();
    int lastState = 0;
    int lastStart = 0;

    final CharSequence buf = baseLexer.getBufferSequence();
    final char[] bufArray = CharArrayUtil.fromSequenceWithoutCopying(buf);

    if (seenTag) {
      FoundEnd:
      while(true) {
        FoundEndOfTag:
        while(baseLexer.getTokenType() != XmlTokenType.XML_END_TAG_START) {
          if (baseLexer.getTokenType() == XmlTokenType.XML_COMMENT_CHARACTERS) {
            // we should terminate on first occurence of </
            final int end = baseLexer.getTokenEnd();

            for(int i = baseLexer.getTokenStart(); i < end; ++i) {
              if ((bufArray != null ? bufArray[i ]:buf.charAt(i)) == '<' &&
                  i + 1 < end &&
                  (bufArray != null ? bufArray[i+1]:buf.charAt(i+1)) == '/') {
                myTokenEnd = i;
                lastStart = i - 1;
                lastState = 0;

                break FoundEndOfTag;
              }
            }
          }

          lastState = baseLexer.getState();
          myTokenEnd = baseLexer.getTokenEnd();
          lastStart = baseLexer.getTokenStart();
          if (myTokenEnd == getBufferEnd()) break FoundEnd;
          baseLexer.advance();
        }

        // check if next is script
        if (baseLexer.getTokenType() != XmlTokenType.XML_END_TAG_START) { // we are inside comment
          baseLexer.start(buf,lastStart+1,getBufferEnd(),lastState);
          baseLexer.getTokenType();
          baseLexer.advance();
        } else {
          baseLexer.advance();
        }

        while(XmlTokenType.WHITESPACES.contains(baseLexer.getTokenType())) {
          baseLexer.advance();
        }

        if (baseLexer.getTokenType() == XmlTokenType.XML_NAME) {
          String name = TreeUtil.getTokenText(baseLexer);
          if (caseInsensitive) name = name.toLowerCase();

          if((hasSeenScript() && XmlNameHandler.TOKEN_SCRIPT.equals(name)) ||
             (hasSeenStyle() && XmlNameHandler.TOKEN_STYLE.equals(name)) ||
             CompletionUtil.DUMMY_IDENTIFIER_TRIMMED.equalsIgnoreCase(name)) {
            break; // really found end
          }
        }
      }

      baseLexer.start(buf,lastStart,getBufferEnd(),lastState);
      baseLexer.getTokenType();
    } else if (seenAttribute) {
      while(true) {
        if (!isValidAttributeValueTokenType(baseLexer.getTokenType())) break;

        myTokenEnd = baseLexer.getTokenEnd();
        lastState = baseLexer.getState();
        lastStart = baseLexer.getTokenStart();

        if (myTokenEnd == getBufferEnd()) break;
        baseLexer.advance();
      }

      baseLexer.start(buf,lastStart,getBufferEnd(),lastState);
      baseLexer.getTokenType();
    }
    return myTokenEnd;
  }

  protected boolean isValidAttributeValueTokenType(final IElementType tokenType) {
    return tokenType == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN ||
      tokenType == XmlTokenType.XML_CHAR_ENTITY_REF;
  }

  public void advance() {
    baseLexer.advance();
    IElementType type = baseLexer.getTokenType();
    TokenHandler tokenHandler = tokenHandlers.get(type);
    if (tokenHandler!=null) tokenHandler.handleElement(this);
  }

  public char[] getBuffer() {
    return baseLexer.getBuffer();
  }

  public int getBufferEnd() {
    return baseLexer.getBufferEnd();
  }

  public IElementType getTokenType() {
    return baseLexer.getTokenType();
  }

  public int getTokenStart() {
    return baseLexer.getTokenStart();
  }

  public int getTokenEnd() {
    return baseLexer.getTokenEnd();
  }

  public int getState() {
    int state = baseLexer.getState();

    state |= ((seenScript)?SEEN_SCRIPT:0);
    state |= ((seenTag)?SEEN_TAG:0);
    state |= ((seenStyle)?SEEN_STYLE:0);
    state |= ((seenAttribute)?SEEN_ATTRIBUTE:0);
    state |= ((seenContentType)?SEEN_CONTENT_TYPE:0);

    return state;
  }

  protected final boolean hasSeenStyle() {
    return seenStyle;
  }

  protected final boolean hasSeenAttribute() {
    return seenAttribute;
  }

  protected final boolean hasSeenTag() {
    return seenTag;
  }

  protected boolean hasSeenScript() {
    return seenScript;
  }

  protected abstract boolean isHtmlTagState(int state);

  protected Lexer getBaseLexer() {
    return baseLexer;
  }

  public CharSequence getBufferSequence() {
    return baseLexer.getBufferSequence();
  }
}
