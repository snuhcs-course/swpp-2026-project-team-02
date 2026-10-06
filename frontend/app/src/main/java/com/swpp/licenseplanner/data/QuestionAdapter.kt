package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.QuestionBankResponse
import com.swpp.licenseplanner.domain.Question
import com.swpp.licenseplanner.domain.UNKNOWN_KEY
import com.swpp.licenseplanner.domain.UNKNOWN_LABEL
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class InvalidQuestionDataException(message: String) : Exception(message)

/** Decodes and validates the public question response (FLOW-04, design D6). */
object QuestionAdapter {
    /** @throws InvalidQuestionDataException for malformed JSON or data that breaks D6's rules. */
    fun parse(body: String, expectedCertificationId: String): List<Question> {
        val response = try {
            val element = ApiJson.parseToJsonElement(body)
            checkJsonTypes(element)
            ApiJson.decodeFromJsonElement(QuestionBankResponse.serializer(), element)
        } catch (error: SerializationException) {
            throw InvalidQuestionDataException("question response could not be parsed: ${error.message}")
        } catch (error: IllegalArgumentException) {
            throw InvalidQuestionDataException("question response could not be parsed: ${error.message}")
        }
        return validate(response, expectedCertificationId)
    }

    /**
     * kotlinx.serialization coerces quoted numbers (`"1"`) into numeric fields; the backend
     * requires real JSON types, so check them on the tree before decoding.
     */
    private fun checkJsonTypes(element: JsonElement) {
        fun invalid(message: String): Nothing = throw InvalidQuestionDataException(message)
        fun JsonElement?.isString() = this is JsonPrimitive && isString
        fun JsonElement?.isNumber() = this is JsonPrimitive && !isString && content != "null" &&
            content != "true" && content != "false"
        val root = element as? JsonObject ?: invalid("response must be an object")
        if (!root["certification_id"].isString()) invalid("certification_id must be a string")
        val questions = root["questions"] as? JsonArray ?: invalid("questions must be an array")
        for (item in questions) {
            val question = item as? JsonObject ?: invalid("each question must be an object")
            val id = question["id"]
            if (!id.isNumber() || (id as JsonPrimitive).content.toIntOrNull() == null) invalid("id must be an integer")
            if (!question["prompt"].isString()) invalid("prompt must be a string")
            val choices = question["choices"] as? JsonObject ?: invalid("choices must be an object")
            if (!choices.values.all { it.isString() }) invalid("choice labels must be strings")
            if (!question["correct_answer"].isString()) invalid("correct_answer must be a string")
            if (!question["possible_score"].isNumber()) invalid("possible_score must be a number")
        }
    }

    fun validate(response: QuestionBankResponse, expectedCertificationId: String): List<Question> {
        fun invalid(message: String): Nothing = throw InvalidQuestionDataException(message)
        if (response.certificationId != expectedCertificationId) invalid("certification_id does not match")
        if (response.questions.isEmpty()) invalid("questions is empty")
        val seen = HashSet<Int>()
        return response.questions.map { dto ->
            if (dto.id < 1) invalid("question id must be positive: ${dto.id}")
            if (!seen.add(dto.id)) invalid("duplicate question id: ${dto.id}")
            if (dto.prompt.isBlank()) invalid("prompt is blank for id ${dto.id}")
            if (dto.choices.isEmpty() || dto.choices.any { (key, label) -> key.isBlank() || label.isBlank() }) {
                invalid("choices are invalid for id ${dto.id}")
            }
            if (dto.correctAnswer !in dto.choices) invalid("correct_answer is not a choice for id ${dto.id}")
            if (dto.correctAnswer == UNKNOWN_KEY) invalid("correct_answer uses the reserved UNKNOWN key for id ${dto.id}")
            dto.choices[UNKNOWN_KEY]?.let { label ->
                if (label != UNKNOWN_LABEL) invalid("choice UNKNOWN conflicts with the reserved label for id ${dto.id}")
            }
            if (!dto.possibleScore.isFinite() || dto.possibleScore <= 0) invalid("possible_score must be positive for id ${dto.id}")
            Question(dto.id, dto.prompt, LinkedHashMap(dto.choices), dto.correctAnswer, dto.possibleScore)
        }
    }
}
