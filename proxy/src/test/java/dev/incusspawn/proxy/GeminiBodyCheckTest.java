package dev.incusspawn.proxy;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** What the Gemini-on-Vertex route admits of a body (#1925): only what Pi's google-vertex provider sends. */
class GeminiBodyCheckTest {

    /** Admits {@code body}, read whole and then a byte at a time, and returns what it passed on each time. */
    static String admitted(String body) throws GeminiBodyCheck.Refused {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var whole = new ByteArrayOutputStream();
        var check = new GeminiBodyCheck(chunk -> whole.writeBytes(chunk.getBytes()));
        check.accept(Buffer.buffer(bytes));
        check.end();
        var single = new ByteArrayOutputStream();
        var byByte = new GeminiBodyCheck(chunk -> single.writeBytes(chunk.getBytes()));
        for (var b : bytes) byByte.accept(Buffer.buffer(new byte[] {b}));
        byByte.end();
        assertArrayEquals(bytes, whole.toByteArray());
        assertArrayEquals(bytes, single.toByteArray());
        return whole.toString(StandardCharsets.UTF_8);
    }

    /** The refusal of {@code body}, the same whether it arrives whole or a byte at a time. */
    static GeminiBodyCheck.Refused refusal(String body) {
        var bytes = body.getBytes(StandardCharsets.ISO_8859_1);
        var whole = assertThrows(GeminiBodyCheck.Refused.class, () -> {
            var check = new GeminiBodyCheck(chunk -> {});
            check.accept(Buffer.buffer(bytes));
            check.end();
        }, body);
        var single = assertThrows(GeminiBodyCheck.Refused.class, () -> {
            var check = new GeminiBodyCheck(chunk -> {});
            for (var b : bytes) check.accept(Buffer.buffer(new byte[] {b}));
            check.end();
        }, body);
        assertEquals(whole.status(), single.status(), body);
        assertEquals(whole.getMessage(), single.getMessage(), body);
        return whole;
    }

    @Test void piBodyPassesUnchanged() throws Exception {
        assertEquals(CredentialProofProxyTest.GEMINI_BODY, admitted(CredentialProofProxyTest.GEMINI_BODY));
    }

    /** Below the positions the check reads, any name goes: a function's arguments and schema, the configs. */
    @ParameterizedTest @ValueSource(strings = {
            "{}",
            " {\"contents\":null,\"systemInstruction\":null,\"tools\":null} ",
            "{\"contents\":[{\"parts\":[{\"functionCall\":{\"name\":\"f\",\"args\":{\"fileData\":{\"fileUri\":\"gs://b\"}}}}]}]}",
            "{\"contents\":[{\"parts\":[{\"functionResponse\":{\"response\":{\"cachedContent\":1,\"parts\":[{\"fileData\":1}]}}}]}]}",
            "{\"tools\":[{\"functionDeclarations\":[{\"parameters\":{\"properties\":{\"fileData\":{},\"retrieval\":{}}}}]}]}",
            "{\"generationConfig\":{\"cachedContent\":\"x\"},\"toolConfig\":{\"fileData\":[1,2.5e-3,true,false,null]}}",
            "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"\\u00e9\\ud83d\\ude00 \\\"fileData\\\"\"}]}]}",
            "{\"contents\":[{\"parts\":null},{\"parts\":[{\"text\":\"\\ud800 lone surrogates in a value are the value's\"}]}]}",
            "{\"contents\":[{\"parts\":[{\"functionResponse\":null}]}],\"tools\":[{\"functionDeclarations\":null}]}"})
    void admitsWhatHoldsNoOtherField(String body) throws Exception {
        assertEquals(body, admitted(body));
    }

    /** Every top-level field but Pi's five is refused by name, in any spelling, never dropped. */
    @ParameterizedTest @ValueSource(strings = {
            "cachedContent", "cached_content", "system_instruction", "generation_config", "safetySettings", "labels",
            "modelArmorConfig", "Contents", "contents ", "model"})
    void refusesAnyOtherTopLevelField(String name) {
        var refused = refusal("{\"contents\":[],\"" + name + "\":\"projects/other/x\"}");
        assertEquals(403, refused.status());
        assertTrue(refused.getMessage().contains("\"" + name + "\""), refused.getMessage());
    }

    /** A name is compared as Vertex decodes it, so an escape spells nothing new. */
    @Test void comparesNamesDecoded() {
        assertTrue(refusal("{\"cached\\u0043ontent\":\"x\"}").getMessage().contains("\"cachedContent\""));
        assertEquals(403, refusal("{\"contents\":[{\"parts\":[{\"fil\\u0065Data\":{}}]}]}").status());
        assertEquals(403, refusal("{\"contents\":[{\"part\\u0073\":[{\"fileData\":{}}]}]}").status());
        assertEquals(403, refusal("{\"tools\":[{\"function\\u0044eclarations\":[],\"google\\u0053earch\":{}}]}").status());
    }

    /** A tool may only declare functions: retrieval, Search, code execution and whatever comes next are refused. */
    @ParameterizedTest @ValueSource(strings = {"retrieval", "googleSearch", "googleSearchRetrieval", "codeExecution",
            "function_declarations", "urlContext"})
    void refusesAnyOtherToolField(String name) {
        var refused = refusal("{\"tools\":[{\"functionDeclarations\":[]},{\"" + name + "\":{}}]}");
        assertEquals(403, refused.status());
        assertTrue(refused.getMessage().contains("tools[].\"" + name + "\""), refused.getMessage());
    }

    /** A part's fileData may point at a bucket: refused in every part the request holds, in either spelling. */
    @ParameterizedTest @ValueSource(strings = {
            "{\"contents\":[{\"parts\":[{\"text\":\"a\"},{\"fileData\":{\"fileUri\":\"gs://b/o\"}}]}]}",
            "{\"contents\":[{\"parts\":[{\"file_data\":{\"file_uri\":\"gs://b/o\"}}]}]}",
            "{\"systemInstruction\":{\"parts\":[{\"fileData\":{}}]}}",
            "{\"contents\":[{\"parts\":[{\"functionResponse\":{\"parts\":[{\"fileData\":{}}]}}]}]}",
            "{\"contents\":[{\"parts\":[{\"function_response\":{\"parts\":[{\"file_data\":{}}]}}]}]}",
            "{\"contents\":[{\"parts\":[{\"fileData\":null}]}]}"})
    void refusesFileDataInAPart(String body) {
        var refused = refusal(body);
        assertEquals(403, refused.status());
        assertTrue(refused.getMessage().contains("a part's file"), refused.getMessage());
    }

    /** Where the check relies on a shape, another shape would hide what it reads: refused. */
    @ParameterizedTest @ValueSource(strings = {
            "[]", "\"x\"", "null", "",  "   ",
            "{\"contents\":{\"parts\":[{\"fileData\":{}}]}}",
            "{\"contents\":[[{\"parts\":[]}]]}",
            "{\"contents\":[\"x\"]}",
            "{\"systemInstruction\":[{\"parts\":[]}]}",
            "{\"systemInstruction\":\"text\"}",
            "{\"contents\":[{\"parts\":{\"fileData\":{}}}]}",
            "{\"contents\":[{\"parts\":[[{\"fileData\":{}}]]}]}",
            "{\"contents\":[{\"parts\":[{\"functionResponse\":[{\"parts\":[]}]}]}]}",
            "{\"tools\":{\"retrieval\":{}}}",
            "{\"tools\":[[{\"retrieval\":{}}]]}",
            "{\"tools\":[1]}"})
    void refusesAShapeItCannotRead(String body) {
        assertEquals(403, refusal(body).status(), body);
    }

    /** Only strict JSON, and all of it: a body cut short or followed by more is not sent. */
    @ParameterizedTest @ValueSource(strings = {
            "{", "{\"generationConfig\":[", "{\"generationConfig\":[]", "{\"generationConfig\":[]}x", "{\"generationConfig\":[]}{}",
            "{'generationConfig':[]}", "{generationConfig:[]}", "{\"generationConfig\":[],}", "{\"generationConfig\":[1,]}", "{\"generationConfig\" 1}",
            "{\"generationConfig\":[01]}", "{\"generationConfig\":[1.]}", "{\"generationConfig\":[-]}", "{\"generationConfig\":[tru]}",
            "{\"generationConfig\":[\"\\x\"]}", "{\"generationConfig\":[\"\\u12g4\"]}", "{\"generationConfig\":[\"a\nb\"]}",
            "{\"generationConfig\":[]}/*c*/", "{\"generationConfig\":[NaN]}"})
    void refusesAnythingButOneCompleteJsonObject(String body) {
        assertEquals(400, refusal(body).status(), body);
    }

    /** A name the check compares must be well-formed Unicode: Vertex's reading of it cannot be guessed. */
    @ParameterizedTest @ValueSource(strings = {
            "{\"\\ud800\":1}", "{\"\\udc00\":1}", "{\"\\ud800x\":1}", "{\"\\ud800\\u0041\":1}",
            "{\"\u00ff\":1}", "{\"\u00c0\u00a0\":1}", "{\"contents\":[{\"parts\":[{\"\u00ed\u00a0\u0080\":1}]}]}"})
    void refusesAMalformedName(String body) {
        var refused = refusal(body);
        assertEquals(403, refused.status());
        assertTrue(refused.getMessage().contains("Unicode"), refused.getMessage());
    }

    /** A guest chose the name: it is shown with nothing that could start or style a line, and cut short. */
    @Test void showsARefusedNameSafely() {
        var refused = refusal("{\"a\\u202eb\\u2028c\\\"" + "x".repeat(200) + "\":1}");
        assertFalse(refused.getMessage().codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT || c == 0x2028), refused.getMessage());
        assertTrue(refused.getMessage().contains("a\\u202eb\\u2028c\\u0022"), refused.getMessage());
        assertTrue(refused.getMessage().contains("…"), refused.getMessage());
        assertTrue(refused.getMessage().length() < 200, refused.getMessage());
    }

    /** A reader that ends a name at a control character would read another name than the check did. */
    @ParameterizedTest @ValueSource(strings = {
            "{\"contents\":[{\"parts\":[{\"fileData\\u0000\":{}}]}]}", "{\"contents\\n\":[]}",
            "{\"tools\":[{\"functionDeclarations\\u007f\":[]}]}", "{\"contents\":[{\"part\u0085s\":[]}]}"})
    void refusesAControlCharacterInAName(String body) {
        var refused = refusal(new String(body.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
        assertEquals(403, refused.status());
        assertTrue(refused.getMessage().contains("control character"), refused.getMessage());
    }

    /** Every string is well-formed UTF-8, so a lenient decoder cannot end one where the check did not. */
    @ParameterizedTest @ValueSource(strings = {
            "{\"contents\":[{\"parts\":[{\"text\":\"\u00c0\u00a2,\"}]}]}",
            "{\"generationConfig\":{\"\u00ff\":1}}",
            "{\"generationConfig\":\"\u00ed\u00a0\u0080\"}",
            "{\"generationConfig\":\"\u00e2\u0082\"}"})
    void refusesMalformedUtf8InAnyString(String body) {
        var refused = refusal(body);
        assertEquals(400, refused.status(), body);
        assertTrue(refused.getMessage().contains("UTF-8"), refused.getMessage());
    }

    /** Memory is bounded: nesting has a limit, and a long name or value is not kept. */
    @Test void boundsWhatItHolds() throws Exception {
        var deep = "{\"generationConfig\":" + "[".repeat(GeminiBodyCheck.MAX_DEPTH - 1) + "]".repeat(GeminiBodyCheck.MAX_DEPTH - 1) + "}";
        assertEquals(deep, admitted(deep));
        assertEquals(400, refusal("{\"generationConfig\":" + "[".repeat(GeminiBodyCheck.MAX_DEPTH) + "]".repeat(GeminiBodyCheck.MAX_DEPTH) + "}").status());
        var longValue = "{\"contents\":[{\"parts\":[{\"inlineData\":{\"data\":\"" + "A".repeat(1 << 20) + "\"}}]}]}";
        assertEquals(longValue.length(), admitted(longValue).length());
    }
}
