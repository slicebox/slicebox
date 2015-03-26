(function () {
    'use strict';
}());

// Declare app level module which depends on views, and components
angular.module('slicebox', [
    'ngRoute',
    'ngCookies',
    'ngAnimate',
    'ngMaterial',
    'ui.bootstrap',
    'slicebox.utils',
    'slicebox.directives',
    'slicebox.login',
    'slicebox.home',
    'slicebox.inbox',
    'slicebox.outbox',
    'slicebox.log',
    'slicebox.adminScps',
    'slicebox.adminWatchDirectories',
    'slicebox.adminBoxes',
    'slicebox.adminUsers'
])

.config(function($locationProvider, $routeProvider, $mdThemingProvider) {
    $locationProvider.html5Mode(true);
    $routeProvider.otherwise({redirectTo: '/'});

    $mdThemingProvider.theme('default')
        .primaryPalette('blue-grey')
        .accentPalette('amber')
        .warnPalette('deep-orange');
})

.controller('SliceboxCtrl', function($scope, $rootScope, $location, $mdSidenav, authenticationService) {

    $scope.uiState = {
        errorMessages: [],
        showMenu: true,
        isAdmin: angular.isDefined($rootScope.globals.currentUser) && 
                 $rootScope.globals.currentUser.role !== 'USER'
    };

    $scope.toggleLeftNav = function() {
        $mdSidenav('leftNav').toggle();
    };

    $scope.logout = function() {
        authenticationService.clearCredentials();
        $scope.uiState.isAdmin = false;
        $scope.uiState.showMenu = false;
        $location.url("/login");
    };

    $scope.userSignedIn = function() {
        return authenticationService.userSignedIn();
    };

    $scope.isCurrentPath = function(path) { 
        return $location.path() === path;
    };

    $scope.currentPathStartsWith = function(path) { 
        return $location.path().indexOf(path) === 0;
    };

    $scope.closeErrorMessageAlert = function(errorIndex) {
        $scope.uiState.errorMessages.splice(errorIndex, 1);
    };

    $scope.appendErrorMessage = function(errorMessage) {
        $scope.uiState.errorMessages.push(errorMessage);
    };

})

.run(function ($rootScope, $location, $cookieStore, $http) {
    // keep user logged in after page refresh
    $rootScope.globals = $cookieStore.get('globals') || {};
    if ($rootScope.globals.currentUser) {
        $http.defaults.headers.common['Authorization'] = 'Basic ' + $rootScope.globals.currentUser.authdata; // jshint ignore:line
    }

    $rootScope.$on('$locationChangeStart', function (event, next, current) {
        // redirect to login page if not logged in
        if ($location.path() !== '/login' && !$rootScope.globals.currentUser) {
            $location.path('/login');
        }
    });
});
