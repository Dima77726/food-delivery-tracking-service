package com.dima.fooddelivery.chat.api;

import com.dima.fooddelivery.chat.domain.ChatMessage;
import com.dima.fooddelivery.chat.service.ChatService;
import com.dima.fooddelivery.common.security.CurrentUser;
import com.dima.fooddelivery.common.security.ResourceAccessGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Чат заказа по HTTP: история и отправка. Живые кадры — в {@code /topic/order.{orderId}.chat}
 * через WebSocket ({@code /ws/tracking}).
 *
 * <p>Права проверяет сервис, а не {@code @PreAuthorize}: участник чата определяется отношением
 * к заказу (см. {@code ChatParticipants}), а не ролью в токене.
 */
@Validated
@RestController
@RequiredArgsConstructor
@Tag(name = "Chat", description = "Чат заказа: клиент, курьер и поддержка")
@SecurityRequirement(name = "bearer-jwt")
public class ChatController {

    private static final int DEFAULT_PAGE = 50;
    private static final int MAX_PAGE = 200;

    private final ChatService chatService;
    private final CurrentUser currentUser;
    private final ResourceAccessGuard access;

    @GetMapping("/api/v1/orders/{orderId}/chat/messages")
    @Operation(summary = "История чата заказа, новые первыми; before — курсор следующей страницы")
    public ChatHistoryResponse history(
            @Positive @PathVariable Long orderId,
            @RequestParam(required = false) UUID before,
            @Min(1) @Max(MAX_PAGE) @RequestParam(defaultValue = "" + DEFAULT_PAGE) int limit
    ) {
        ChatService.History history = chatService.history(orderId, currentUser.requireId(), access.isAdmin(), before, limit);

        List<ChatMessageResponse> messages = history.messages().stream().map(ChatMessageResponse::of).toList();
        UUID nextBefore = messages.size() == limit ? messages.getLast().messageId() : null;

        return new ChatHistoryResponse(messages, history.reads(), nextBefore);
    }

    /**
     * Повтор с тем же {@code clientMessageId} вернёт уже записанное сообщение и не разошлёт его
     * второй раз — клиент может смело повторять отправку после обрыва связи.
     */
    @PostMapping("/api/v1/orders/{orderId}/chat/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Отправить сообщение в чат заказа (идемпотентно по clientMessageId)")
    public ChatMessageResponse send(
            @Positive @PathVariable Long orderId,
            @Valid @RequestBody SendChatMessageRequest request
    ) {
        ChatMessage message = chatService.send(
                orderId, currentUser.requireId(), access.isAdmin(), request.text(), request.clientMessageId());

        return ChatMessageResponse.of(message);
    }
}
