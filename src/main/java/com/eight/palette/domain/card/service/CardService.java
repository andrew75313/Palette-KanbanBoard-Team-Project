package com.eight.palette.domain.card.service;

import com.eight.palette.domain.card.dto.CardRequestDto;
import com.eight.palette.domain.card.dto.CardResponseDto;
import com.eight.palette.domain.card.entity.Card;
import com.eight.palette.domain.card.repository.CardRepository;
import com.eight.palette.domain.column.entity.ColumnInfo;
import com.eight.palette.domain.column.repository.ColumnsRepository;
import com.eight.palette.global.config.RedissonConfig;
import com.eight.palette.global.exception.BadRequestException;
import com.eight.palette.global.exception.NotFoundException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class CardService {

    private final CardRepository cardRepository;
    private final ColumnsRepository columnsRepository;
    private final RedissonClient redissonClient;

    private static final String LOCK_KEY = "cardLock";

    public CardService(CardRepository cardRepository, ColumnsRepository columnsRepository, RedissonClient redissonClient) {

        this.cardRepository = cardRepository;
        this.columnsRepository = columnsRepository;
        this.redissonClient = redissonClient;
    }

    @Transactional
    public CardResponseDto createCard(Long columnId, CardRequestDto requestDto) {

        ColumnInfo columnInfo = columnsRepository.findByIdWithLock(columnId).orElseThrow(()
                -> new NotFoundException("해당 컬럼을 찾지 못했습니다.")
        );

        int cardSize = columnInfo.getCardList().size();
        int position = 1;

        if (cardSize != 0) {
            position = cardSize + 1;
        }

        Card card = Card.builder()
                .title(requestDto.getTitle())
                .content(requestDto.getContent())
                .deadLineDate(requestDto.getDeadLineDate())
                .worker(requestDto.getWorker())
                .columnInfo(columnInfo)
                .status(Card.Status.ACTIVE)
                .position(position)
                .build();

        cardRepository.save(card);

        return new CardResponseDto(card);
    }

    @Transactional
    public CardResponseDto updateCard(Long cardId, CardRequestDto requestDto) {

        RLock lock = redissonClient.getFairLock(LOCK_KEY);

        Card foundCard = findCard(cardId);

        try {
            boolean isLocked = lock.tryLock(RedissonConfig.WAIT_TIME, RedissonConfig.LEASE_TIME, TimeUnit.SECONDS);
            if (isLocked) {
                try {
                    foundCard.updateTitle(requestDto.getTitle());

                    cardRepository.save(foundCard);
                } finally {
                    lock.unlock();
                }
            } else {
                throw new BadRequestException("다시 시도해 주세요.(Lock 얻기 실패)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("다시 시도해 주세요.(작업 실패)");
        }

        return new CardResponseDto(foundCard);

    }

    @Transactional
    public void moveCard(Long cardId, Integer newPosition) {

        RLock lock = redissonClient.getFairLock(LOCK_KEY);

        try {
            boolean isLocked = lock.tryLock(RedissonConfig.WAIT_TIME, RedissonConfig.LEASE_TIME, TimeUnit.SECONDS);
            if (isLocked) {
                try {
                    Card foundCard = findCard(cardId);

                    if (foundCard.getPosition() == newPosition) {
                        return;
                    }

                    List<Card> cardList = cardRepository.findAllByColumnInfoAndStatusOrderByPositionAsc(foundCard.getColumnInfo(), Card.Status.ACTIVE);

                    Card card = cardList.stream().filter(c -> c.getPosition() == newPosition).findAny().orElseThrow(
                            () -> new BadRequestException("해당 위치로는 옮길 수 없습니다.")
                    );

                    int temp = newPosition;

                    card.updatePosition(foundCard.getPosition());

                    foundCard.updatePosition(temp);

                    cardRepository.save(foundCard);

                    cardRepository.save(card);
                } finally {
                    lock.unlock();
                }
            } else {
                throw new BadRequestException("다시 시도해 주세요.(Lock 얻기 실패)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("다시 시도해 주세요.(작업 실패)");
        }

    }

    @Transactional
    public void moveColumnCard(Long columnId, Long cardId) {

        RLock lock = redissonClient.getFairLock(LOCK_KEY);

        Card foundCard = findCard(cardId);

        ColumnInfo newColumn = columnsRepository.findById(columnId).orElseThrow(()
                -> new NotFoundException("해당 컬럼을 찾지 못했습니다.")
        );

        try {
            boolean isLocked = lock.tryLock(RedissonConfig.WAIT_TIME, RedissonConfig.LEASE_TIME, TimeUnit.SECONDS);
            if (isLocked) {
                try {
                    foundCard.updateColumn(newColumn);

                    int cardSize = newColumn.getCardList().size();
                    int position = 1;

                    if (cardSize != 0) {
                        position = cardSize + 1;
                    }

                    foundCard.updatePosition(position);

                    cardRepository.save(foundCard);
                } finally {
                    lock.unlock();
                }
            } else {
                throw new BadRequestException("다시 시도해 주세요.(Lock 얻기 실패)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("다시 시도해 주세요.(작업 실패)");
        }

    }

    @Transactional
    public void deleteCard(Long cardId) {

        RLock lock = redissonClient.getFairLock(LOCK_KEY);

        Card foundCard = findCard(cardId);

        if (foundCard.getStatus() == Card.Status.DELETED) {
            throw new BadRequestException("이미 삭제된 카드입니다.");
        }

        try {
            boolean isLocked = lock.tryLock(RedissonConfig.WAIT_TIME, RedissonConfig.LEASE_TIME, TimeUnit.SECONDS);
            if (isLocked) {
                try {
                    foundCard.delete();

                    cardRepository.save(foundCard);
                } finally {
                    lock.unlock();
                }
            } else {
                throw new BadRequestException("다시 시도해 주세요.(Lock 얻기 실패)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("다시 시도해 주세요.(작업 실패)");
        }

    }

    public Card findCard(Long cardId) {

        Card foundCard = cardRepository.findById(cardId).orElseThrow(()
                -> new NotFoundException("해당 카드를 찾지 못했습니다.")
        );

        if (foundCard.getStatus() == Card.Status.DELETED) {
            throw new BadRequestException("이미 삭제된 카드입니다.");
        }

        return foundCard;

    }

    public List<CardResponseDto> getAllCards() {

        List<Card> cardList = cardRepository.findAllByStatus(Card.Status.ACTIVE);

        return cardList.stream().map(CardResponseDto::new).toList();

    }

    public List<CardResponseDto> getCardsByWorker(String worker) {

        List<Card> cardList = cardRepository.findAllByWorkerAndStatus(worker, Card.Status.ACTIVE);

        return cardList.stream().map(CardResponseDto::new).toList();

    }

    public List<CardResponseDto> getCardsByColumn(Long columnId) {

        ColumnInfo column = columnsRepository.findById(columnId).orElseThrow(()
                -> new NotFoundException("해당 컬럼을 찾지 못했습니다.")
        );

        List<Card> cardList = cardRepository.findAllByColumnInfoAndStatusOrderByPositionAsc(column, Card.Status.ACTIVE);

        return cardList.stream().map(CardResponseDto::new).toList();

    }
}
