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
    if (!gridElement || gridElement.children.length > 0) return;
    for (let y = 0; y < GRID_SIZE; y++) {
        for (let x = 0; x < GRID_SIZE; x++) {
            const tile = document.createElement('div');
            tile.classList.add('tile');
            tile.id = `tile-${x}-${y}`;
            if (x < 4) tile.onclick = () => handleTileClick(x, y);
            else tile.classList.add('symmetric-zone');
            gridElement.appendChild(tile);
            mapData.push({ x, y, tileType: 'EMPTY' });
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

function updateTile(x, y, type) {
    const tileObj = mapData.find(t => t.x === x && t.y === y);
    tileObj.tileType = type;
    const el = document.getElementById(`tile-${x}-${y}`);
    el.className = `tile ${type} ${x >= 4 ? 'symmetric-zone' : ''}`;
    el.innerText = type === 'EMPTY' ? '' : (type === 'MY_TILE' ? '내꺼' : (type === 'ENEMY_TILE' ? '적꺼' : '벽'));
}

function selectType(type, e) {
    selectedType = type;
    document.querySelectorAll('.tool-btn').forEach(b => b.classList.remove('active'));
    e.target.classList.add('active');
}

function saveMap() {
    let tilesArray = [];
    let hasEmpty = false;

    // 1. 2차원 배열을 순회하며 데이터 수집
    for (let y = 0; y < 8; y++) {
        for (let x = 0; x < 8; x++) {
            const tile = mapData[y][x];
            if (tile === 'EMPTY' || !tile) {
                hasEmpty = true;
            }
            tilesArray.push(tile); // "MY_TILE" 등을 그대로 저장
        }
    }

    // 2. 빈 공간 검사
    if (hasEmpty) {
        alert("⚠️ 모든 칸을 채워야 저장할 수 있습니다!");
        return;
    }

    // 3. 쉼표로 이어 붙이기 (가독성 버전)
    const fullMapString = tilesArray.join(",");

    // 4. 서버 전송
    fetch(`${SERVER_URL}/api/map/save`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            mapData: fullMapString, // 64개의 단어가 쉼표로 연결된 상태
            creatorUid: currentUser.firebaseUid // 현재 로그인한 유저 UID
        })
    })
    .then(res => {
        if (res.ok) {
            alert("✅ 전장이 성공적으로 저장되었습니다!");
            navTo('home'); // 저장 후 홈으로 이동
        } else {
            alert("❌ 저장 실패 (이미 존재하는 맵일 수 있습니다)");
        }
    })
    .catch(err => console.error("통신 오류:", err));
}

function loadMapToGrid(fullMapString) {
    // 쉼표를 기준으로 다시 64개의 배열로 쪼갬
    const tiles = fullMapString.split(",");

    for (let i = 0; i < tiles.length; i++) {
        const x = i % 8;
        const y = Math.floor(i / 8);
        mapData[y][x] = tiles[i]; // "MY_TILE" 등이 그대로 들어감
    }
    renderGrid(); // 화면 갱신
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
    // 1. 모든 게임 섹션들을 리스트로 만듭니다.
    const allSections = ['auth-form', 'home-screen', 'editor-section', 'deck-section'];
    
    // 2. 일단 모든 섹션을 보이지 않게 처리합니다.
    allSections.forEach(id => {
        const element = document.getElementById(id);
        if (element) element.style.display = 'none';
    });
    
    // 3. 사용자가 요청한 페이지(섹션)만 켭니다.
    if (page === 'editor') {
        document.getElementById('editor-section').style.display = 'block';
        initMap(); // 에디터 초기화
    } 
    else if (page === 'battle') {
        alert("⚔️ 전장 준비 중입니다! 주사위 시스템을 먼저 구축해볼까요?");
        document.getElementById('home-screen').style.display = 'block'; // 다시 홈으로
    } 
    else if (page === 'deck') {
        // 나중에 만들 덱 화면
        document.getElementById('deck-section').style.display = 'block'; 
        // alert("🎲 덱 구성 시스템 준비 중입니다.");
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

window.addEventListener('DOMContentLoaded', () => {
    //초기화 실행
    setupFirebase();
});
