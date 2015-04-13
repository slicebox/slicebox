(function () {
    'use strict';
}());

// Declare app level module which depends on views, and components
angular.module('slicebox', [
    'ngRoute',
    'ngCookies',
    'ngAnimate',
    'ngMaterial',
    'slicebox.utils',
    'slicebox.directives',
    'slicebox.login',
    'slicebox.home',
    'slicebox.transactions',
    'slicebox.log',
    'slicebox.adminPacs',
    'slicebox.adminWatchDirectories',
    'slicebox.adminBoxes',
    'slicebox.adminUsers'
])

.config(function($locationProvider, $routeProvider, $mdThemingProvider, $animateProvider) {
    $locationProvider.html5Mode(true);
    $routeProvider.otherwise({redirectTo: '/'});

    $mdThemingProvider.theme('default')
        .primaryPalette('blue-grey', {
            'default': '600',
        })
        .accentPalette('pink')
        .warnPalette('red');

    $mdThemingProvider.theme('redTheme')
        .primaryPalette('red');

    // prevent ng-animate on spinners, causes weird behaviour with ng-if/ng-show
    $animateProvider.classNameFilter(/^((?!(fa-spinner)).)*$/);
})

.filter('prettyPatientName', function () {
    return function (text) {
        return text ? text.replace(new RegExp('\\^', 'g'), ' ') : '';
    };
})

.controller('SliceboxCtrl', function($scope, $rootScope, $location, $mdSidenav, $mdToast, authenticationService) {

    $scope.uiState = {
        showMenu: true
    };

    $scope.toggleLeftNav = function() {
        $mdSidenav('leftNav').toggle();
    };

    $scope.logout = function() {
        authenticationService.clearCredentials();
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

    $scope.showErrorMessage = function(errorMessage) {
        var toast = $mdToast.simple()
            .content(errorMessage)
            .action('Dismiss')
            .highlightAction(true)
            .hideDelay(30000)
            .theme('redTheme')
            .position("bottom right");
        $mdToast.show(toast);
    };

    $scope.showInfoMessage = function(infoMessage) {
        var toast = {
            parent: angular.element(document.getElementById("content")),
            template: '<md-toast>' + infoMessage + '</md-toast>',
            position: 'top right'
        };
        $mdToast.show(toast);
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
