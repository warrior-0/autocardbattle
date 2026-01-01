const GRID_SIZE = 8;
let mapData = []; 
let selectedType = 'MY_TILE';
let currentUser = null;
let isSignupMode = false; // 기본값은 로그인 모드

// 1. 서버 주소 설정 (Render 서버 주소)
const SERVER_URL = "https://autocardbattle.onrender.com";

// 2. Firebase 초기화 및 설정
async function setupFirebase() {
    try {
        const response = await fetch(`${SERVER_URL}/api/config/firebase`, {
            headers: { 'Origin': window.location.origin }
        });
        const config = await response.json();
        firebase.initializeApp(config);

        // 인증 상태 확인
        firebase.auth().onAuthStateChanged((user) => {
            if (user) {
                // 이미 로그인된 상태라면 서버 로그인 진행
                handleServerLogin(user);
            }
        });
    } catch (error) {
        console.error("Firebase 로딩 실패:", error);
    }
}

// 3. [에러 해결 핵심] 폼 전환 로직 (toggleAuthMode)
// HTML의 onclick="toggleAuthMode(event)"와 이름이 정확히 일치해야 합니다.
function toggleAuthMode(e) {
    if (e) e.preventDefault();
    isSignupMode = !isSignupMode;
    
    const title = document.getElementById('auth-title');
    const btn = document.getElementById('main-auth-btn');
    const nickGroup = document.getElementById('nickname-group');
    const switchText = document.getElementById('auth-switch-text');

    if (isSignupMode) {
        title.innerText = "회원가입";
        btn.innerText = "회원가입 하기";
        nickGroup.style.display = "block";
        switchText.innerHTML = '이미 계정이 있나요? <a href="#" onclick="toggleAuthMode(event)">로그인</a>';
    } else {
        title.innerText = "로그인";
        btn.innerText = "로그인";
        nickGroup.style.display = "none";
        switchText.innerHTML = '계정이 없으신가요? <a href="#" onclick="toggleAuthMode(event)">회원가입</a>';
    }
}

// 4. 통합 인증 실행 (로그인/회원가입 버튼 클릭 시)
async function handleAuthAction() {
    const email = document.getElementById('user-email').value;
    const password = document.getElementById('user-password').value;
    const nickname = document.getElementById('user-nickname').value;

    if (!email || !password) return alert("이메일과 비밀번호를 입력하세요.");

    try {
        if (isSignupMode) {
            // 회원가입 모드
            if (!nickname) return alert("닉네임을 입력하세요!");
            const result = await firebase.auth().createUserWithEmailAndPassword(email, password);
            
            // 서버 DB에 닉네임과 UID 등록
            const response = await fetch(`${SERVER_URL}/api/user/login`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ uid: result.user.uid, username: nickname })
            });

            if (response.ok) {
                alert("회원가입 성공! 로그인 해주세요.");
                isSignupMode = false;
                toggleAuthMode(); // 로그인 모드로 전환
            }
        } else {
            // 로그인 모드
            const result = await firebase.auth().signInWithEmailAndPassword(email, password);
            handleServerLogin(result.user);
        }
    } catch (error) {
        alert("오류: " + error.message);
    }
}

// 서버 세션 로그인 처리
async function handleServerLogin(firebaseUser) {
    try {
        const response = await fetch(`${SERVER_URL}/api/user/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ uid: firebaseUser.uid })
        });

        if (response.ok) {
            currentUser = await response.json();
            showEditor();
        }
    } catch (error) {
        console.error("서비 로그인 실패:", error);
    }
}

function showEditor() {
    document.getElementById('auth-form').style.display = 'none';
    document.getElementById('editor-section').style.display = 'block';
    const userDisplay = document.getElementById('user-display');
    if (userDisplay) userDisplay.innerText = `${currentUser.username}님 접속 중`;
    initMap();
}

function handleLogout() {
    firebase.auth().signOut().then(() => {
        window.location.reload();
    });
}

// 5. 맵 에디터 관련 로직 (기존 코드 유지)
function initMap() {
    const gridElement = document.getElementById('map-grid');
    if (!gridElement) return;

    // 1. [중요] 기존 UI와 데이터를 완전히 초기화합니다.
    gridElement.innerHTML = ''; 
    mapData = []; 

    for (let y = 0; y < GRID_SIZE; y++) {
        for (let x = 0; x < GRID_SIZE; x++) {
            // UI 생성
            const tile = document.createElement('div');
            tile.classList.add('tile');
            tile.id = `tile-${x}-${y}`;
            
            // 왼쪽 4칸만 클릭 가능 (대칭 시스템 유지)
            if (x < 4) {
                tile.onclick = () => handleTileClick(x, y);
            } else {
                tile.classList.add('symmetric-zone');
            }
            
            gridElement.appendChild(tile);

            // 2. [중요] 데이터 배열에 객체 추가
            mapData.push({ x, y, tileType: 'EMPTY' });
        }
    }
}

function updateTile(x, y, type) {
    // 3. [핵심] find를 통해 정확한 객체를 찾아 업데이트
    const tileObj = mapData.find(t => t.x === x && t.y === y);
    if (tileObj) {
        tileObj.tileType = type;
        const el = document.getElementById(`tile-${x}-${y}`);
        if (el) {
            // 클래스 초기화 후 재설정
            el.className = `tile ${type} ${x >= 4 ? 'symmetric-zone' : ''}`;
            // 텍스트 표시
            el.innerText = type === 'EMPTY' ? '' : (type === 'MY_TILE' ? '내 타일' : (type === 'ENEMY_TILE' ? '적 타일' : '벽'));
        }
    }
}

function handleTileClick(x, y) {
    const type = selectedType;
    updateTile(x, y, type);
    const symX = 7 - x;
    let symType = type;
    if (type === 'MY_TILE') symType = 'ENEMY_TILE';
    else if (type === 'ENEMY_TILE') symType = 'MY_TILE';
    updateTile(symX, y, symType);
}

function selectType(type, e) {
    selectedType = type;
    document.querySelectorAll('.tool-btn').forEach(b => b.classList.remove('active'));
    e.target.classList.add('active');
}

function saveMap() {
    // 1. mapData 배열에서 tileType만 추출
    const tilesArray = mapData.map(t => t.tileType);
    
    // 2. 빈 공간('EMPTY')이 하나라도 있는지 단순 체크
    if (tilesArray.includes('EMPTY')) {
        alert("⚠️ 아직 비어있는 칸이 있습니다. 모든 칸을 채워주세요!");
        return;
    }

    // 3. 모든 칸이 채워졌다면 쉼표로 합치기
    const fullMapString = tilesArray.join(",");
    
    // 4. 서버 전송
    fetch(`${SERVER_URL}/api/map/save`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            mapData: fullMapString,
            creatorUid: currentUser.firebaseUid
        })
    }).then(res => {
        if (res.ok) {
            alert("✅ 전장이 성공적으로 저장되었습니다!");
            navTo('home'); // 저장 후 홈 화면으로 이동
        } else {
            alert("❌ 저장 실패: 서버 오류가 발생했습니다.");
        }
    }).catch(err => {
        console.error("통신 오류:", err);
    });
}

// script.js 내 loadMapToGrid 함수 수정
function loadMapToGrid(fullMapString) {
    if (!fullMapString) return;
    
    const tiles = fullMapString.split(",");
    const gridElement = document.getElementById('map-grid');
    gridElement.innerHTML = ''; // 기존 그리드 삭제
    mapData = []; // 데이터 초기화

    tiles.forEach((type, i) => {
        const x = i % GRID_SIZE;
        const y = Math.floor(i / GRID_SIZE);
        
        // 1. 데이터 배열 업데이트
        mapData.push({ x, y, tileType: type });

        // 2. UI 생성
        const tile = document.createElement('div');
        tile.id = `tile-${x}-${y}`;
        tile.className = `tile ${type}`; // DB에서 가져온 타입(MY_TILE 등) 적용
        
        // 텍스트 표시
        if (type === 'MY_TILE') tile.innerText = "내 타일";
        else if (type === 'ENEMY_TILE') tile.innerText = "적 타일";
        else if (type === 'WALL') tile.innerText = "벽";

        // 3. 전투 중이라면 배치 클릭 이벤트 연결
        tile.onclick = () => onTileClickForBattle(x, y);
        
        gridElement.appendChild(tile);
    });
}

// script.js 수정 및 추가
function showHome() {
    // 모든 섹션 숨기기
    document.getElementById('auth-form').style.display = 'none';
    document.getElementById('editor-section').style.display = 'none';
    // 홈 화면 보이기
    document.getElementById('home-screen').style.display = 'block';
    
    const name = (currentUser && currentUser.username) ? currentUser.username : "무명용사";
    document.getElementById('welcome-msg').innerText = `${name}님, 전장에 오신 것을 환영합니다!`;
}

// 메뉴 이동 함수
function navTo(page) {
    // 1. 제어해야 할 모든 섹션과 UI 요소를 포함합니다.
    const allSections = [
        'auth-form', 
        'home-screen', 
        'editor-section', 
        'deck-section', 
        'battle-header', 
        'battle-hand-section'
    ];
    
    // 2. 모든 요소를 숨깁니다.
    allSections.forEach(id => {
        const element = document.getElementById(id);
        if (element) element.style.display = 'none';
    });
    
    // 3. 페이지별 맞춤 화면 설정
    if (page === 'editor') {
        document.getElementById('editor-section').style.display = 'block';
        // 에디터 도구 표시
        document.querySelector('.palette').style.display = 'flex';
        document.querySelector('.actions').style.display = 'block';
        const h2 = document.querySelector('#editor-section h2');
        if (h2) h2.innerText = "🏗️ 맵 빌더";
        initMap(); 
    } 
    else if (page === 'battle') {
        // 전투 전용 UI 표시
        document.getElementById('battle-header').style.display = 'flex';
        document.getElementById('battle-hand-section').style.display = 'block';
        
        const editorSection = document.getElementById('editor-section');
        if (editorSection) {
            editorSection.style.display = 'block';
            const h2 = editorSection.querySelector('h2');
            if (h2) h2.innerText = "⚔️ 실시간 전장";

            // [중요] 타일 클릭을 배치용으로 변경하여 편집 차단
            document.querySelectorAll('.tile').forEach(tile => {
                const coords = tile.id.split('-');
                const x = parseInt(coords[1]);
                const y = parseInt(coords[2]);
                tile.onclick = () => onTileClickForBattle(x, y);
            });
            
            // 에디터 도구 숨기기
            document.querySelector('.palette').style.display = 'none';
            document.querySelector('.actions').style.display = 'none';
        }
        startMatch(); 
    } 
    else if (page === 'deck') {
        // ✅ 무한 루프 방지: 단순히 섹션만 보여줍니다.
        document.getElementById('deck-section').style.display = 'block';
    } 
    else if (page === 'home') {
        document.getElementById('home-screen').style.display = 'block';
    }
}

// handleServerLogin 성공 시 showHome 호출로 변경
async function handleServerLogin(firebaseUser, providedNickname = null) {
    try {
        const response = await fetch(`${SERVER_URL}/api/user/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ 
                uid: firebaseUser.uid, 
                username: providedNickname 
            })
        });

        if (response.ok) {
            currentUser = await response.json();
            showHome(); // 에디터 대신 홈으로 이동
        }
    } catch (error) {
        console.error("서버 통신 실패:", error);
    }
}

//주사위 덱 로직
let allDice = [];      // DB에서 가져온 전체 주사위 정보
let selectedDice = []; // 현재 유저가 선택한 덱 (타입명 리스트)

async function showDeckEditor() {
    navTo('deck');
    
    // 1. 유저의 기존 덱 정보 초기화 (DB에서 불러온 값 활용)
    if (currentUser && currentUser.selectedDeck) {
        selectedDice = currentUser.selectedDeck.split(",").filter(d => d !== "");
    } else {
        selectedDice = [];
    }

    try {
        // 2. 전체 주사위 목록 불러오기
        const res = await fetch(`${SERVER_URL}/api/dice/list`);
        allDice = await res.json();

        renderDeckUI();
    } catch (err) {
        console.error("데이터 로드 실패:", err);
    }
}

// 화면을 다시 그리는 핵심 함수
function renderDeckUI() {
    const currentDeckDiv = document.getElementById('current-deck');
    const diceListDiv = document.getElementById('dice-list');
    
    currentDeckDiv.innerHTML = "";
    diceListDiv.innerHTML = "";

    // 1. 하단: 전체 주사위 목록 출력
    allDice.forEach(dice => {
        // 이미 덱에 포함된 주사위는 목록에서 비활성화 효과를 줄 수 있습니다.
        const isSelected = selectedDice.includes(dice.diceType);
        const card = createDiceCard(dice, isSelected);
        
        card.onclick = () => {
            if (isSelected) return alert("이미 덱에 포함되어 있습니다.");
            if (selectedDice.length >= 5) return alert("덱은 최대 5개까지입니다.");
            
            selectedDice.push(dice.diceType);
            renderDeckUI(); // 다시 그리기
        };
        diceListDiv.appendChild(card);
    });

    // 2. 상단: 내 현재 덱 출력
    selectedDice.forEach(type => {
        const diceInfo = allDice.find(d => d.diceType === type);
        if (diceInfo) {
            const card = createDiceCard(diceInfo, false);
            card.classList.add('in-deck');
            card.onclick = () => {
                // 클릭 시 덱에서 제거
                selectedDice = selectedDice.filter(d => d !== type);
                renderDeckUI(); // 다시 그리기
            };
            currentDeckDiv.appendChild(card);
        }
    });
}

// 주사위 카드 HTML 생성 도우미
function createDiceCard(dice, isSelected) {
    const card = document.createElement('div');
    card.className = `dice-card ${isSelected ? 'disabled' : ''}`;
    card.style.borderColor = dice.color;
    card.innerHTML = `
        <div class="dice-icon" style="color:${dice.color}">🎲</div>
        <h4>${dice.name}</h4>
        <p class="dice-desc">${dice.description}</p>
        <div class="dice-stats">공격력:${dice.damage} | 사거리:${dice.range}</div>
    `;
    return card;
}

// 덱을 DB에 저장
async function saveUserDeck() {
    if (selectedDice.length !== 5) return alert("주사위 5개를 모두 골라주세요!");

    const deckString = selectedDice.join(","); // 저장할 덱 문자열

    const res = await fetch(`${SERVER_URL}/api/user/deck/save`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            uid: currentUser.firebaseUid,
            deck: deckString
        })
    });
    
    if (res.ok) {
        // [핵심] DB 저장 성공 후, 현재 메모리의 유저 정보도 업데이트해줍니다.
        if (currentUser) {
            currentUser.selectedDeck = deckString;
        }
        
        alert("✅ 나만의 덱이 저장되었습니다!");
        renderDeckUI();
    } else {
        alert("❌ 덱 저장에 실패했습니다.");
    }
}

// 전투 매칭 시작 (웹소켓 연결 후 방에 입장)
let currentRoomId = null;

// [수정] startMatch 함수
async function startMatch() {
    if (!currentUser) return alert("로그인이 필요합니다.");

    // 1. [핵심] 다른 어떤 작업보다 '상대를 찾는 중' 오버레이를 먼저 띄웁니다.
    const overlay = document.getElementById('matching-overlay');
    if (overlay) {
        overlay.style.display = 'flex';
    }

    // 브라우저가 UI를 그릴 시간을 아주 잠깐(0.1초) 줍니다.
    await new Promise(resolve => setTimeout(resolve, 100));

    try {
        // 2. 그 다음 서버에 매칭을 요청합니다.
        const res = await fetch(`${SERVER_URL}/api/battle/match?userUid=${currentUser.firebaseUid}`, {
            method: 'POST'
        });

        if (res.status === 200) {
            const data = await res.json();
            currentRoomId = data.roomId;
            
            // 매칭 성공 시 오버레이 숨기기
            if (overlay) overlay.style.display = 'none';

            // 이후 로직 진행 (웹소켓 연결 및 맵 데이터 로드)
            connectWebSocket();
            
            const startRes = await fetch(`${SERVER_URL}/api/battle/start?userUid=${currentUser.firebaseUid}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(currentUser.selectedDeck.split(","))
            });
            const startData = await startRes.json();
            
            // [주의] data 구조에 맞춰서 맵 데이터 로드
            if (startData.mapData && startData.mapData.length > 0) {
                loadMapToGrid(startData.mapData[0].mapData);
            }
            
            myHand = startData.hand;
            currentTurn = 1;
            navTo('battle'); 
            renderHand();

        } else if (res.status === 202) {
            // 아직 매칭 중이면 5초 뒤에 이 함수를 다시 실행
            // 이때 오버레이는 이미 켜져 있으므로 그대로 유지됩니다.
            setTimeout(startMatch, 5000);
        }
    } catch (err) {
        console.error("매칭 요청 오류:", err);
        if (overlay) overlay.style.display = 'none'; // 에러 시에는 가려줌
    }
}

// 매칭 취소 함수
function cancelMatch() {
    if (matchTimer) clearTimeout(matchTimer); // 다음 재시도 중단
    const overlay = document.getElementById('matching-overlay');
    if (overlay) overlay.style.display = 'none'; // 오버레이 닫기
    navTo('home'); // 홈으로 이동
}

function connectWebSocket() {
    if (!currentRoomId) return;

    const socket = new SockJS(`${SERVER_URL}/ws`);
    stompClient = Stomp.over(socket);

    stompClient.connect({}, function (frame) {
        // 내 방 ID 전용 채널만 구독하여 다른 방 유저와 격리
        stompClient.subscribe(`/topic/battle/${currentRoomId}`, function (message) {
            const data = JSON.parse(message.body);
            handleBattleMessage(data);
        });
    });
}

function onTileClickForBattle(x, y) {
    if (!currentRoomId) return;

    const payload = {
        type: "PLACE",
        sender: currentUser.firebaseUid,
        x: x, y: y,
        diceType: selectedDiceFromHand,
        turn: currentTurn
    };

    // 현재 방 ID 경로로 메시지 전송
    stompClient.send(`/app/battle/${currentRoomId}/place`, {}, JSON.stringify(payload));
}

// 서버에서 오는 실시간 메시지 처리기 (handleBattleMessage 보완)
function handleBattleMessage(data) {
    switch(data.type) {
        case "MATCH_FOUND": // 매칭 성공 및 맵 정보 수신
            loadMapToGrid(data.mapData);
            myHand = data.hand;
            currentTurn = 1;
            renderHand();
            alert("상대를 찾았습니다! 배치를 시작하세요.");
            break;

        case "TURN_PROGRESS": // 양쪽 모두 배치 완료되어 다음 턴 진행
            myHand = data.nextHand;
            currentTurn = data.nextTurn;
            renderHand();
            alert(`${data.nextTurn}턴이 시작되었습니다.`);
            break;

        case "REVEAL": // 3턴 종료, 전체 전장 공개
            renderFullMap(data.allPlacements); // 상대 주사위까지 다 그리기
            startFight(); // 전투 애니메이션 시작
            break;
            
        case "OPPONENT_READY": // 상대방이 배치를 마쳤다는 알림 (심리적 요소)
            console.log("상대방이 배치를 완료하고 기다리고 있습니다.");
            break;
    }
}

let myHp = 5;
let enemyHp = 5;

function handleBattleMessage(data) {
    if (data.type === "REVEAL") {
        // 1. 서버가 보내준 전체 배치 데이터(누적분)를 순회
        data.allPlacements.forEach(p => {
            const tile = document.getElementById(`tile-${p.x}-${p.y}`);
            
            // [수정] 맵 데이터에서 해당 좌표의 타일 타입을 찾습니다.
            const mapInfo = mapData.find(m => m.x === p.x && m.y === p.y);
            
            if (tile && mapInfo) {
                // 주사위 텍스트 설정 (타입명이 있다면 표시)
                tile.innerText = getDiceEmoji(p.diceType); 
                
                // 타일 타입에 따른 색상 적용 (기본 맵 스타일 유지)
                if (mapInfo.tileType === 'MY_TILE') {
                    tile.style.backgroundColor = "#3498db"; // 내 진영 푸른색
                    tile.style.color = "white";
                } else if (mapInfo.tileType === 'ENEMY_TILE') {
                    tile.style.backgroundColor = "#e74c3c"; // 적 진영 붉은색
                    tile.style.color = "white";
                }
                
                // 배치된 주사위라는 것을 알리기 위해 클래스 추가 (애니메이션 등 활용)
                tile.classList.add('placed-dice');
            }
        });

        applyDamage(data.loserUid);
        currentTurn = 1;
        
        // 2. 다음 배치를 위해 선택 상태 초기화
        selectedDiceFromHand = null;
        renderHand(); 
        
        alert("전투 종료! 살아남은 주사위들이 다음 라운드에도 유지됩니다.");
    }
}

// 주사위 타입에 따른 이모지 반환 (선택 사항)
function getDiceEmoji(type) {
    const emojis = {
        'FIRE': '🔥',
        'WIND': '🌪️',
        'ELECTRIC': '⚡',
        'SWORD': '⚔️',
        'SNIPER': '🎯'
    };
    return emojis[type] || "🎲";
}

function applyDamage(loserUid) {
    // 1. 무승부 판정
    if (loserUid === "NONE") {
        resetForNextRound(); // 체력 깎지 않고 다음 라운드 준비
        return;
    }

    // 2. 패배자 체력 차감
    if (loserUid === currentUser.firebaseUid) {
        myHp--;
        updateHpUI('my-hp', myHp);
    } else {
        enemyHp--;
        updateHpUI('enemy-hp', enemyHp);
    }

    // 3. 최종 승패 확인 후 라운드 초기화
    checkGameOver();
    resetForNextRound();
}

function updateHpUI(elementId, hp) {
    const hpBar = document.getElementById(elementId);
    hpBar.innerText = "❤️".repeat(hp) + "🖤".repeat(5 - hp);
}

window.addEventListener('DOMContentLoaded', () => {
    //초기화 실행
    setupFirebase();
});
