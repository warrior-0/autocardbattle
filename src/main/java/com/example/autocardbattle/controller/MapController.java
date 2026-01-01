@RestController
@RequestMapping("/api/map")
@CrossOrigin(origins = "https://warrior-0.github.io")
public class MapController {

    @Autowired
    private MapRepository mapRepository;

    @PostMapping("/save")
    public ResponseEntity<?> saveMap(@RequestBody MapRequest request) {
        try {
            MapEntity map = new MapEntity();
            map.setCreatorUid(request.getCreatorUid());
            map.setMapData(request.getMapData());
            
            mapRepository.save(map);
            return ResponseEntity.ok("Saved");
        } catch (Exception e) {
            // 중복된 맵(Unique 제약 조건)일 경우 에러 처리
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Duplicate or Error");
        }
    }
}
