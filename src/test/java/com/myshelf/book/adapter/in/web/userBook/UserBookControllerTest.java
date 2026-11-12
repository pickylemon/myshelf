package com.myshelf.book.adapter.in.web.userBook;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.myshelf.auth.adapter.in.security.ProblemDetailAuthenticationEntryPoint;
import com.myshelf.book.application.port.in.reading.UserBookUpdateReadingStatusCommand;
import com.myshelf.book.application.port.in.reading.UserBookUpdateReadingStatusUseCase;
import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import com.myshelf.global.config.SecurityConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

@WebMvcTest(UserBookController.class)
@Import({SecurityConfig.class, ProblemDetailAuthenticationEntryPoint.class})
class UserBookControllerTest {
  @Autowired MockMvc mockMvc;
  @MockitoBean UserBookUpdateReadingStatusUseCase useCase;
  @MockitoBean JwtDecoder jwtDecoder;
  @MockitoBean AuthenticationManager authenticationManager;

  @ParameterizedTest
  @EnumSource(ReadingStatus.class)
  void 독서_상태를_Command로_전달하고_204를_반환한다(ReadingStatus readingStatus) throws Exception {
    UUID userId = UUID.randomUUID();
    UUID bookId = UUID.randomUUID();
    mockMvc
        .perform(
            patch("/user-books/{id}", bookId)
                .with(jwt().jwt(token -> token.subject(userId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"readingStatus\":\"" + readingStatus.name() + "\"}"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    verify(useCase).update(new UserBookUpdateReadingStatusCommand(userId, bookId, readingStatus));
  }

  /** 상태 누락·null·알 수 없는 enum은 UseCase 호출 전에 거부한다. */
  @ParameterizedTest
  @ValueSource(
      strings = {"{}", "{\"readingStatus\":null}", "{\"readingStatus\":\"UNKNOWN\"}", "null", "{"})
  void 잘못된_독서_상태_요청은_400이다(String body) throws Exception {
    mockMvc
        .perform(
            patch("/user-books/{id}", UUID.randomUUID())
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(useCase);
  }

  /** 새 상태 변경 API의 미인증 요청도 필터에서 차단한다. */
  @Test
  void 토큰이_없으면_401이다() throws Exception {
    mockMvc
        .perform(
            patch("/user-books/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"readingStatus\":\"READ\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));
    verifyNoInteractions(useCase);
  }

  /** 존재하지 않거나 타인 소유인 도서는 동일한 404 계약을 사용한다. */
  @Test
  void 도서_부재_예외는_404_ProblemDetail이다() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID bookId = UUID.randomUUID();
    doThrow(new BookException(BookErrorCode.USER_BOOK_NOT_FOUND))
        .when(useCase)
        .update(new UserBookUpdateReadingStatusCommand(userId, bookId, ReadingStatus.READ));
    mockMvc
        .perform(
            patch("/user-books/{id}", bookId)
                .with(jwt().jwt(token -> token.subject(userId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"readingStatus\":\"READ\"}"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("USER_BOOK_NOT_FOUND"));
  }

  /** 경로 ID 변환 실패는 비즈니스 로직에 전달하지 않는다. */
  @Test
  void 도서_ID가_UUID가_아니면_400이다() throws Exception {
    mockMvc
        .perform(
            patch("/user-books/not-a-uuid")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"readingStatus\":\"READ\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(useCase);
  }
}
