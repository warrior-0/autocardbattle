@RestController
@RequestMapping("/api/battle")
public class BattleController {
    
    @Autowired private MapRepository mapRepository;

    // 1. 전투 시작: 랜덤 맵과 첫 번째 주사위 2개 지급
    @GetMapping("/start")
    public BattleResponse startBattle(@RequestParam String userUid, @RequestBody List<DiceType> userDeck) {
        // DB에서 랜덤 맵 하나 선택
        MapEntity randomMap = mapRepository.findRandomMap().orElseThrow();
        
        // 덱 5개 중 랜덤 2개 셔플
        Collections.shuffle(userDeck);
        List<DiceType> hand = userDeck.subList(0, 2);

        return new BattleResponse(randomMap.getMapData(), hand, 1); // 1턴 시작
    }

    // 2. 배치 정보 수신 및 다음 주사위 지급 (3턴 반복)
    @PostMapping("/place")
    public TurnResponse placeDice(@RequestBody PlacementRequest request) {
        // 서버에 유저의 배치 저장 (DB 혹은 세션)
        // ... save placement ...

        if (request.getTurn() < 3) {
            // 다음 턴을 위한 새로운 랜덤 주사위 2개
            Collections.shuffle(request.getUserDeck());
            return new TurnResponse(request.getUserDeck().subList(0, 2), request.getTurn() + 1);
        } else {
            // 3턴 종료 시 "REVEAL(공개)" 신호 전송
            return new TurnResponse("REVEAL");
        }
    }
}
