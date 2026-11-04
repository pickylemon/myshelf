package com.myshelf.book.adapter.in.web.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.myshelf.auth.adapter.in.security.ProblemDetailAuthenticationEntryPoint;
import com.myshelf.book.application.dto.AddedBook;
import com.myshelf.book.application.port.in.registration.ShelfBookAddCommand;
import com.myshelf.book.application.port.in.registration.ShelfBookAddUseCase;
import com.myshelf.book.domain.ReadingStatus;
import com.myshelf.book.error.BookErrorCode;
import com.myshelf.book.exception.BookException;
import com.myshelf.global.config.SecurityConfig;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ShelfBookAddController.class)
@Import({SecurityConfig.class, ProblemDetailAuthenticationEntryPoint.class})
class ShelfBookAddControllerTest {
  @Autowired MockMvc mockMvc;
  @MockitoBean ShelfBookAddUseCase useCase;
  @MockitoBean JwtDecoder jwtDecoder;
  @MockitoBean AuthenticationManager authenticationManager;

  // TODO(테스트 구현예졍): 같은 ISBN을 두 번 포함한 요청이 400으로 거부되고 UseCase가 호출되지 않는지 검증한다.

  @Test
  void JWT의_사용자와_서재_ID와_도서_목록을_Command로_전달하고_응답을_반환한다() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID shelfId = UUID.randomUUID();
    UUID firstLink = UUID.randomUUID();
    UUID secondLink = UUID.randomUUID();
    when(useCase.add(any()))
        .thenReturn(
            List.of(new AddedBook("first", firstLink), new AddedBook("second", secondLink)));

    mockMvc
        .perform(
            post("/shelves/{shelfId}/books", shelfId)
                .with(jwt().jwt(token -> token.subject(userId.toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"books":[{"isbn":"first","readingStatus":"READ"},
                               {"isbn":"second","readingStatus":"WANT_TO_READ"}]}
                    """))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.books.length()").value(2))
        .andExpect(jsonPath("$.books[0].isbn").value("first"))
        .andExpect(jsonPath("$.books[0].shelfBookId").value(firstLink.toString()))
        .andExpect(jsonPath("$.books[1].isbn").value("second"))
        .andExpect(jsonPath("$.books[1].shelfBookId").value(secondLink.toString()));

    ArgumentCaptor<ShelfBookAddCommand> captor = ArgumentCaptor.forClass(ShelfBookAddCommand.class);
    verify(useCase).add(captor.capture());
    assertThat(captor.getValue())
        .isEqualTo(
            new ShelfBookAddCommand(
                userId,
                shelfId,
                List.of(
                    new ShelfBookAddCommand.BookInput("first", ReadingStatus.READ),
                    new ShelfBookAddCommand.BookInput("second", ReadingStatus.WANT_TO_READ))));
  }

  @ParameterizedTest
  @MethodSource("invalidBodies")
  void 잘못된_요청은_UseCase_호출_전에_400으로_거부한다(String body) throws Exception {
    mockMvc
        .perform(
            post("/shelves/{shelfId}/books", UUID.randomUUID())
                .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(useCase);
  }

  static Stream<String> invalidBodies() {
    return Stream.of(
        "{}",
        "{\"books\":null}",
        "{\"books\":[]}",
        "{\"books\":[null]}",
        "{\"books\":[{\"readingStatus\":\"READ\"}]}",
        "{\"books\":[{\"isbn\":\" \",\"readingStatus\":\"READ\"}]}",
        "{\"books\":[{\"isbn\":\"isbn\"}]}",
        "{\"books\":[{\"isbn\":\"isbn\",\"readingStatus\":null}]}",
        "{\"books\":[{\"isbn\":\"isbn\",\"readingStatus\":\"UNKNOWN\"}]}");
  }

  @Test
  void 인증되지_않은_등록은_401로_거부한다() throws Exception {
    mockMvc
        .perform(
            post("/shelves/{shelfId}/books", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isUnauthorized())
        .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE));

    verifyNoInteractions(useCase);
  }

  @Test
  void 서재_ID가_UUID가_아니면_400으로_거부한다() throws Exception {
    mockMvc
        .perform(
            post("/shelves/not-a-uuid/books")
                .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(useCase);
  }

  @Test
  void 처리한_연결이_없으면_빈_배열을_반환한다() throws Exception {
    when(useCase.add(any())).thenReturn(List.of());

    mockMvc
        .perform(
            post("/shelves/{shelfId}/books", UUID.randomUUID())
                .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.books").isArray())
        .andExpect(jsonPath("$.books").isEmpty());
  }

  @Test
  void 등록_충돌_예외는_409_ProblemDetail로_응답한다() throws Exception {
    when(useCase.add(any())).thenThrow(new BookException(BookErrorCode.DUPLICATE_REGISTRATION));

    mockMvc
        .perform(
            post("/shelves/{shelfId}/books", UUID.randomUUID())
                .with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody()))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("DUPLICATE_REGISTRATION"));
  }

  private String validBody() {
    return "{\"books\":[{\"isbn\":\"isbn\",\"readingStatus\":\"READ\"}]}";
  }
}
