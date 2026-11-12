package com.myshelf.book.adapter.in.web.shelfBook;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.myshelf.auth.adapter.in.security.ProblemDetailAuthenticationEntryPoint;
import com.myshelf.book.application.port.in.shelf.ShelfBookDeleteCommand;
import com.myshelf.book.application.port.in.shelf.ShelfBookRemoveUseCase;
import com.myshelf.book.application.port.in.shelf.ShelfBookUpdateCommand;
import com.myshelf.book.application.port.in.shelf.ShelfBookUpdateUseCase;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import com.myshelf.global.config.SecurityConfig;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** JSON 입력·Command 전달·HTTP 계약만 검증한다. 실제 DB 변경은 통합 테스트에서 확인한다. */
@WebMvcTest(ShelfBookController.class)
@Import({SecurityConfig.class, ProblemDetailAuthenticationEntryPoint.class})
class ShelfBookControllerTest {
  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;
  @MockitoBean ShelfBookUpdateUseCase updateUseCase;
  @MockitoBean ShelfBookRemoveUseCase removeUseCase;
  @MockitoBean JwtDecoder jwtDecoder;
  @MockitoBean AuthenticationManager authenticationManager;

  @Test
  void 메모가_최대_길이를_초과하면_400이며_변경하지_않는다() throws Exception {
    UUID userId = UUID.randomUUID();
    mockMvc
        .perform(
            patch("/shelf-books/{id}", UUID.randomUUID())
                .with(jwt().jwt(token -> token.subject(userId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new ShelfBookMemoUpdateRequest("메".repeat(2001)))))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(updateUseCase);
  }

  /** null·빈 문자열·최대 길이 메모를 JSON으로 받아 인증 사용자와 함께 전달한다. */
  @ParameterizedTest
  @MethodSource("validMemos")
  void 메모_JSON을_Command로_전달하고_204를_반환한다(String memo) throws Exception {
    UUID userId = UUID.randomUUID();
    UUID linkId = UUID.randomUUID();
    mockMvc
        .perform(
            patch("/shelf-books/{id}", linkId)
                .with(jwt().jwt(token -> token.subject(userId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ShelfBookMemoUpdateRequest(memo))))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    verify(updateUseCase).updateMemo(new ShelfBookUpdateCommand(userId, linkId, memo));
  }

  static Stream<String> validMemos() {
    return Stream.of(null, "", "서재별 메모", "메".repeat(2000));
  }

  /** 필드가 없는 객체는 현재 계약에 따라 메모 제거로 전달한다. */
  @Test
  void 메모_필드_누락은_null로_전달한다() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID linkId = UUID.randomUUID();
    mockMvc
        .perform(
            patch("/shelf-books/{id}", linkId)
                .with(jwt().jwt(token -> token.subject(userId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNoContent());
    verify(updateUseCase).updateMemo(new ShelfBookUpdateCommand(userId, linkId, null));
  }

  /** 삭제 대상 ID 목록과 JWT 사용자를 구분해 전달한다. */
  @Test
  void 다건_삭제_Command를_전달하고_204를_반환한다() throws Exception {
    UUID userId = UUID.randomUUID();
    List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
    mockMvc
        .perform(
            delete("/shelf-books")
                .with(jwt().jwt(token -> token.subject(userId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ShelfBookDeleteRequest(ids))))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    verify(removeUseCase).delete(new ShelfBookDeleteCommand(userId, ids));
  }

  /** 빈 목록·null·잘못된 UUID·중복 ID를 삭제 UseCase 호출 전에 거부한다. */
  @ParameterizedTest
  @MethodSource("invalidDeletes")
  void 잘못된_삭제_요청은_400이다(String body) throws Exception {
    mockMvc
        .perform(
            delete("/shelf-books")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(removeUseCase, updateUseCase);
  }

  static Stream<String> invalidDeletes() {
    UUID id = UUID.randomUUID();
    return Stream.of(
        "{}",
        "{\"shelfBookIds\":null}",
        "{\"shelfBookIds\":[]}",
        "{\"shelfBookIds\":[null]}",
        "{\"shelfBookIds\":[\"bad-id\"]}",
        "{\"shelfBookIds\":[\"" + id + "\",\"" + id + "\"]}");
  }

  /** 새 관리 API도 토큰이 없으면 Controller 전에 차단한다. */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void 미인증_메모_수정과_삭제는_401이다(boolean deleting) throws Exception {
    var request = deleting ? delete("/shelf-books") : patch("/shelf-books/{id}", UUID.randomUUID());
    mockMvc
        .perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));
    verifyNoInteractions(updateUseCase, removeUseCase);
  }

  /** 대상 부재·소유권 거부 예외를 공통 Book ProblemDetail로 반환한다. */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void 메모_수정과_삭제의_대상_부재는_404이다(boolean deleting) throws Exception {
    UUID userId = UUID.randomUUID();
    UUID linkId = UUID.randomUUID();
    var failure = new BookException(BookErrorCode.SHELF_BOOK_NOT_FOUND);
    var request = deleting ? delete("/shelf-books") : patch("/shelf-books/{id}", linkId);
    String body;
    if (deleting) {
      doThrow(failure)
          .when(removeUseCase)
          .delete(new ShelfBookDeleteCommand(userId, List.of(linkId)));
      body = objectMapper.writeValueAsString(new ShelfBookDeleteRequest(List.of(linkId)));
    } else {
      doThrow(failure)
          .when(updateUseCase)
          .updateMemo(new ShelfBookUpdateCommand(userId, linkId, "메모"));
      body = "{\"memo\":\"메모\"}";
    }
    mockMvc
        .perform(
            request
                .with(jwt().jwt(token -> token.subject(userId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("SHELF_BOOK_NOT_FOUND"));
  }
}
